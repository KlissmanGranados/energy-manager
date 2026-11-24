Actúa como un Desarrollador Full-Stack Senior experto en SQLite, java y JavaScript vainilla. Tu objetivo es implementar por completo una solución de registro y visualización de cortes eléctricos siguiendo estrictamente un flujo de desarrollo en 4 Fases secuenciales.

No saltes de fase hasta completar la anterior con código funcional y libre de errores.

---

### Fase 1: Base de Datos y Persistencia
Implementa el esquema relacional en SQLite y los métodos de inserción base.

1. **Esquema DDL (con llaves foráneas activas `PRAGMA foreign_keys = ON;`):**
   - Tabla `power_history_type` (Catálogo):
     - `id`: INTEGER, Primary Key, Autoincrement.
     - `name`: TEXT, Not Null (Valores iniciales obligatorios: 'OUTAGE', 'BACK_ON').
   - Tabla `power_history` (Registro):
     - `id`: INTEGER, Primary Key, Autoincrement.
     - `date_time`: TEXT (Formato ISO-8601 UTC), Not Null.
     - `type_id`: INTEGER, Foreign Key -> `power_history_type(id)`, Not Null.

2. **Entregables de la Fase 1:**
   - Script DDL completo en SQL.
   - Sentencias `INSERT` iniciales para el catálogo.
   - Función de ejemplo en Python para registrar eventos ('OUTAGE' y 'BACK_ON').

---

### Fase 2: Lógica de Registro Automático
- Asegura que el sistema backend cuente con la lógica necesaria para insertar registros en la tabla `power_history` cada vez que ocurra un evento de corte o restablecimiento eléctrico, asociando correctamente su respectivo `type_id`.

---

### Fase 3: Endpoints del Backend
Implementa los siguientes endpoints en el servidor:

1. **`GET /api/power_history`**
   - **Query Parameters Obligatorios:**
     - `page_size`: Entero. Por defecto `50`. Restricción: $0 < page\_size \le 50$.
     - `page`: Entero. Por defecto `1`. Valores: `1, 2, 3, ...`
   - **Query Parameters Opcionales:**
     - `type_id`: Filtrar por tipo de evento.
     - `date_start`: Filtrar fecha de inicio (ISO-8601).
     - `date_end`: Filtrar fecha de fin (ISO-8601).
     - `sort`: Formato `field:asc` o `field:desc` (ej. `date_time:desc`).
   - **Estructura de Respuesta JSON obligatoria:**
     ```json
     {
       "metadata": {
         "page_size": 50,
         "page": 1,
         "total_elements": 120
       },
       "results": [
         {
           "id": 1,
           "date_time": "2026-10-16T18:00:00Z",
           "type": {
             "id": 1,
             "name": "OUTAGE"
           }
         }
       ]
     }
     ```

2. **`GET /power_dashboard`**
   - Debe retornar el archivo estático HTML creado en la Fase 4.

---

### Fase 4: Interfaz de Usuario
Crea un único archivo HTML llamado `power_dashboard.html` que incluya embebido todo el CSS y todo el JavaScript necesario.

**Requisitos del Dashboard (`power_dashboard.html`):**
- **Consumo de API:** Debe realizar peticiones fetch a `/api/power_history` manejando la paginación y los filtros de la Fase 3.
- **Visualización de Datos:** Tabla interactiva con el historial de eventos.
- **Métricas Clave (KPIs):** Tarjetas de resumen en la parte superior (ej. total de cortes, duración promedio, estado actual del servicio).
- **Filtros en UI:** Controles visuales (inputs de fecha, selects) para interactuar con los parámetros opcionales del endpoint.
- **Auto-Contenido:** Todo el código (HTML + CSS + JS) debe vivir exclusivamente dentro de este único fichero, sin dependencias de archivos externos locales de estilos o lógica.
