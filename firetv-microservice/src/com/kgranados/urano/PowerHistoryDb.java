package com.kgranados.urano;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Historial de cortes electricos en SQLite (filesDir/../databases/power_history.db).
 *
 *   power_history_type(id, name)            catalogo: 1 = OUTAGE, 2 = BACK_ON
 *   power_history(id, date_time, type_id)   date_time ISO-8601 UTC "yyyy-MM-ddTHH:mm:ssZ"
 *
 * Una sola instancia (ControlService): escribe el hilo de AutoCorteMonitor y leen los
 * hilos del servidor HTTP; SQLiteDatabase es thread-safe con un unico helper.
 */
public class PowerHistoryDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "power_history.db";
    private static final int DB_VERSION = 1;

    private static final String SELECT_EVENTOS =
            "SELECT h.id, h.date_time, t.id, t.name FROM power_history h"
            + " JOIN power_history_type t ON t.id = h.type_id";

    public PowerHistoryDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);   // PRAGMA foreign_keys = ON
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE power_history_type ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " name TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE power_history ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " date_time TEXT NOT NULL,"
                + " type_id INTEGER NOT NULL REFERENCES power_history_type(id))");
        db.execSQL("CREATE INDEX idx_power_history_date ON power_history(date_time)");
        db.execSQL("INSERT INTO power_history_type(id, name) VALUES ("
                + PowerHistoryQuery.TIPO_OUTAGE + ", 'OUTAGE'), ("
                + PowerHistoryQuery.TIPO_BACK_ON + ", 'BACK_ON')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // version 1: sin migraciones todavia
    }

    /** Registra un evento (OUTAGE / BACK_ON) con la hora dada (epoch ms). */
    public void registrar(int typeId, long epochMs) {
        ContentValues v = new ContentValues();
        v.put("date_time", PowerHistoryQuery.iso(epochMs));
        v.put("type_id", typeId);
        getWritableDatabase().insertOrThrow("power_history", null, v);
    }

    /** Evento mas reciente, o null si no hay ninguno. */
    public ResumenCortes.Evento ultimoEvento() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, date_time, type_id FROM power_history ORDER BY date_time DESC, id DESC LIMIT 1", null);
        try {
            if (!c.moveToFirst()) return null;
            return new ResumenCortes.Evento(c.getLong(0),
                    PowerHistoryQuery.parseIso(c.getString(1), "date_time"), c.getInt(2));
        } finally {
            c.close();
        }
    }

    /** Respuesta de GET /api/power_history. */
    public JSONObject consultar(PowerHistoryQuery q) throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        String where = q.where(true);
        String[] args = q.args(true);
        String filtro = where != null ? " WHERE " + where : "";

        long total;
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM power_history h" + filtro, args);
        try {
            total = c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }

        JSONArray results = new JSONArray();
        c = db.rawQuery(SELECT_EVENTOS + filtro + " ORDER BY " + q.orderBy()
                + " LIMIT " + q.pageSize + " OFFSET " + q.offset(), args);
        try {
            while (c.moveToNext()) results.put(evento(c));
        } finally {
            c.close();
        }

        JSONObject meta = new JSONObject();
        meta.put("page_size", q.pageSize);
        meta.put("page", q.page);
        meta.put("total_elements", total);
        JSONObject r = new JSONObject();
        r.put("metadata", meta);
        r.put("results", results);
        return r;
    }

    /**
     * Respuesta de GET /api/power_history/summary: KPIs del rango de fechas (type_id no
     * aplica: las duraciones necesitan ambos tipos) y el ultimo evento global.
     */
    public JSONObject resumen(PowerHistoryQuery q) throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        ResumenCortes k = ResumenCortes.calcular(eventosCronologicos(db, q),
                eventoAntes(db, q), backOnDespues(db, q));

        JSONObject r = new JSONObject();
        r.put("total_outages", k.totalCortes);
        r.put("closed_outages", k.cortesCerrados);
        r.put("avg_duration_sec", k.promedioSeg);
        r.put("max_duration_sec", k.maxSeg);
        r.put("total_duration_sec", k.totalSeg);
        r.put("ongoing_since", k.enCursoDesdeMs >= 0 ? PowerHistoryQuery.iso(k.enCursoDesdeMs) : JSONObject.NULL);

        Cursor c = db.rawQuery(SELECT_EVENTOS + " ORDER BY h.date_time DESC, h.id DESC LIMIT 1", null);
        try {
            r.put("last_event", c.moveToFirst() ? evento(c) : JSONObject.NULL);
        } finally {
            c.close();
        }
        return r;
    }

    /**
     * Respuesta de GET /api/power_history/outages: cada OUTAGE agrupado con su BACK_ON
     * (ver ResumenCortes.emparejar), filtrado por status, ordenado y paginado. Se pagina
     * en memoria porque un par puede cruzar el borde de una pagina de eventos.
     */
    public JSONObject cortes(PowerHistoryQuery q) throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        List<ResumenCortes.Corte> todos = ResumenCortes.filtrarYOrdenar(
                ResumenCortes.emparejar(eventosCronologicos(db, q), eventoAntes(db, q), backOnDespues(db, q)), q);

        JSONArray results = new JSONArray();
        int desde = Math.min(q.offset(), todos.size());
        for (ResumenCortes.Corte k : todos.subList(desde, Math.min(desde + q.pageSize, todos.size()))) {
            JSONObject o = new JSONObject();
            o.put("outage_id", k.outageId >= 0 ? k.outageId : JSONObject.NULL);
            o.put("start", k.inicioMs >= 0 ? PowerHistoryQuery.iso(k.inicioMs) : JSONObject.NULL);
            o.put("back_on_id", k.backOnId >= 0 ? k.backOnId : JSONObject.NULL);
            o.put("end", k.finMs >= 0 ? PowerHistoryQuery.iso(k.finMs) : JSONObject.NULL);
            o.put("duration_sec", k.durSeg >= 0 ? k.durSeg : JSONObject.NULL);
            o.put("status", k.estado);
            o.put("started_before_range", k.antesDelRango);
            results.put(o);
        }

        JSONObject meta = new JSONObject();
        meta.put("page_size", q.pageSize);
        meta.put("page", q.page);
        meta.put("total_elements", todos.size());
        JSONObject r = new JSONObject();
        r.put("metadata", meta);
        r.put("results", results);
        return r;
    }

    /** Eventos del rango de fechas de q (type_id no aplica), en orden cronologico. */
    private static List<ResumenCortes.Evento> eventosCronologicos(SQLiteDatabase db, PowerHistoryQuery q) {
        String where = q.where(false);
        List<ResumenCortes.Evento> eventos = new ArrayList<ResumenCortes.Evento>();
        Cursor c = db.rawQuery("SELECT h.id, h.date_time, h.type_id FROM power_history h"
                + (where != null ? " WHERE " + where : "")
                + " ORDER BY h.date_time ASC, h.id ASC", q.args(false));
        try {
            while (c.moveToNext()) {
                eventos.add(new ResumenCortes.Evento(c.getLong(0),
                        PowerHistoryQuery.parseIso(c.getString(1), "date_time"), c.getInt(2)));
            }
        } finally {
            c.close();
        }
        return eventos;
    }

    /**
     * Estado al empezar el rango de q: el inicio (primer OUTAGE tras el ultimo BACK_ON) del
     * corte que seguia abierto, el ultimo BACK_ON si habia luz, o null si no hay "Desde" o
     * no hay eventos antes.
     */
    private static ResumenCortes.Evento eventoAntes(SQLiteDatabase db, PowerHistoryQuery q) {
        if (q.dateStart == null) return null;
        ResumenCortes.Evento ultimo = primero(db, "date_time < ?", "DESC", q.dateStart);
        if (ultimo == null || ultimo.tipo != PowerHistoryQuery.TIPO_OUTAGE) return ultimo;
        ResumenCortes.Evento backOn = primero(db, "date_time < ? AND type_id = " + PowerHistoryQuery.TIPO_BACK_ON,
                "DESC", q.dateStart);
        if (backOn == null) {
            return primero(db, "date_time < ? AND type_id = " + PowerHistoryQuery.TIPO_OUTAGE, "ASC", q.dateStart);
        }
        return primero(db, "date_time < ? AND type_id = " + PowerHistoryQuery.TIPO_OUTAGE
                + " AND (date_time > ? OR (date_time = ? AND id > ?))", "ASC",
                q.dateStart, PowerHistoryQuery.iso(backOn.ms), PowerHistoryQuery.iso(backOn.ms),
                String.valueOf(backOn.id));
    }

    /** Primer BACK_ON despues del rango de q, o null si no hay "Hasta" o aun no volvio la luz. */
    private static ResumenCortes.Evento backOnDespues(SQLiteDatabase db, PowerHistoryQuery q) {
        if (q.dateEnd == null) return null;
        return primero(db, (q.dateEndExclusive ? "date_time >= ?" : "date_time > ?")
                + " AND type_id = " + PowerHistoryQuery.TIPO_BACK_ON, "ASC", q.dateEnd);
    }

    /** Primer evento que cumple where en orden cronologico (ASC) o inverso (DESC). */
    private static ResumenCortes.Evento primero(SQLiteDatabase db, String where, String dir, String... args) {
        Cursor c = db.rawQuery("SELECT id, date_time, type_id FROM power_history WHERE " + where
                + " ORDER BY date_time " + dir + ", id " + dir + " LIMIT 1", args);
        try {
            if (!c.moveToFirst()) return null;
            return new ResumenCortes.Evento(c.getLong(0),
                    PowerHistoryQuery.parseIso(c.getString(1), "date_time"), c.getInt(2));
        } finally {
            c.close();
        }
    }

    /** Fila de SELECT_EVENTOS -> {"id", "date_time", "type": {"id", "name"}} */
    private static JSONObject evento(Cursor c) throws Exception {
        JSONObject tipo = new JSONObject();
        tipo.put("id", c.getInt(2));
        tipo.put("name", c.getString(3));
        JSONObject e = new JSONObject();
        e.put("id", c.getLong(0));
        e.put("date_time", c.getString(1));
        e.put("type", tipo);
        return e;
    }
}
