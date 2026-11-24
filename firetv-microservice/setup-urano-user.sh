#!/usr/bin/env bash
# Configura (y endurece) en Urano la cuenta 'urano-power' que usa el stick.
# Lo UNICO que puede hacer esa cuenta es apagar Urano, y solo via SSH desde el stick:
#   - shell de login que no abre sesion (solo ejecuta el apagado con el comando forzado)
#   - sudo solo para '/usr/sbin/poweroff' SIN argumentos
#   - authorized_keys y home propiedad de root (la cuenta no puede cambiar su llave)
#   - llave con restrict + command= + from=<ip del stick>
#   - bloque 'Match User' en sshd con ForceCommand y sin tty/forwarding
# Idempotente: se puede correr varias veces.
#
# Correr con:  ! bash /home/kgranados/firetv-microservice/setup-urano-user.sh
set -euo pipefail

# Llave publica del stick (RSA: JSch en Android 9 no soporta ed25519)
PUB="$(cat "$(dirname "$0")/config/id_urano.pub")"
STICK_IP='192.168.0.7'

ssh -o ConnectTimeout=8 root@kgranados.com "PUB='$PUB' STICK_IP='$STICK_IP' bash -s" <<'REMOTE'
set -euo pipefail
U=urano-power
H=/home/$U
CMD='sudo /usr/sbin/poweroff'
SHELLW=/usr/local/sbin/urano-power-shell
SUDOF=/etc/sudoers.d/urano-power
SSHDF=/etc/ssh/sshd_config.d/50-urano-power.conf

# 0) usuario del sistema, sin contraseña de login
if ! id "$U" >/dev/null 2>&1; then
  useradd -m -c "Urano power-off bot" "$U"
fi
passwd -l "$U" >/dev/null

# 1) shell de login que no abre sesion
cat > "$SHELLW" <<'SH'
#!/bin/sh
# Shell de login de urano-power: no abre sesiones ni ejecuta comandos arbitrarios.
# Unico caso permitido: sshd la invoca como  <shell> -c 'sudo /usr/sbin/poweroff'
# (comando forzado de la llave del stick / ForceCommand).
if [ -n "${SSH_CONNECTION:-}" ] && [ "$#" -eq 2 ] && [ "$1" = "-c" ] \
   && [ "$2" = "sudo /usr/sbin/poweroff" ]; then
  exec /usr/bin/sudo -n /usr/sbin/poweroff
fi
echo "urano-power: esta cuenta solo sirve para apagar Urano desde el stick." >&2
exit 1
SH
chown root:root "$SHELLW"
chmod 755 "$SHELLW"
sh -n "$SHELLW"
usermod -s "$SHELLW" "$U"

# 2) sudo: solo poweroff y SIN argumentos ("" = no se permiten argumentos)
TMP=$(mktemp)
printf '%s ALL=(root) NOPASSWD: /usr/sbin/poweroff ""\n' "$U" > "$TMP"
visudo -cf "$TMP" >/dev/null
install -m 440 -o root -g root "$TMP" "$SUDOF"
rm -f "$TMP"

# 3) llave del stick: restrict + solo apagar + solo desde la IP del stick.
#    home, .ssh y authorized_keys propiedad de root (legibles por la cuenta, no escribibles)
install -d -m 711 -o root -g root "$H"
install -d -m 711 -o root -g root "$H/.ssh"
printf 'restrict,command="%s",from="%s" %s\n' "$CMD" "$STICK_IP" "$PUB" > "$H/.ssh/authorized_keys"
chown root:root "$H/.ssh/authorized_keys"
chmod 644 "$H/.ssh/authorized_keys"
rm -f "$H/.bashrc" "$H/.profile" "$H/.bash_logout"

# 4) sshd: Match User con ForceCommand y sin tty/forwarding.
#    Salvaguarda: la config efectiva de root debe quedar IDENTICA; si no, se revierte sin recargar.
rm -f "$SSHDF"
ROOT_BEFORE=$(sshd -T -C user=root,host=pc,addr=192.168.0.4 | sort)
cat > "$SSHDF" <<'CONF'
# Cuenta del stick (urano-power): solo puede apagar Urano.
Match User urano-power
    AuthenticationMethods publickey
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    PermitTTY no
    AllowTcpForwarding no
    AllowStreamLocalForwarding no
    AllowAgentForwarding no
    X11Forwarding no
    PermitTunnel no
    PermitUserRC no
    ForceCommand sudo /usr/sbin/poweroff
CONF
chmod 644 "$SSHDF"
if ! sshd -t; then
  rm -f "$SSHDF"; echo "ERROR: sshd -t fallo; drop-in revertido, sshd NO recargado." >&2; exit 1
fi
ROOT_AFTER=$(sshd -T -C user=root,host=pc,addr=192.168.0.4 | sort)
if [ "$ROOT_BEFORE" != "$ROOT_AFTER" ]; then
  rm -f "$SSHDF"; echo "ABORTADO: el bloque Match alteraba la config de root; revertido, sshd NO recargado." >&2; exit 1
fi
# (sin tuberia: con pipefail, 'sshd -T | grep -q' da SIGPIPE=141 aunque la linea exista)
EFF=$(sshd -T -C user="$U",host=stick,addr="$STICK_IP")
if ! grep -qi '^forcecommand sudo /usr/sbin/poweroff$' <<<"$EFF"; then
  rm -f "$SSHDF"; echo "ABORTADO: el bloque Match no aplico a $U; revertido, sshd NO recargado." >&2; exit 1
fi
systemctl reload ssh

# 5) verificacion (pruebas seguras: ninguna apaga Urano)
echo "=== verificacion ==="
getent passwd "$U"
stat -c '%A %U:%G %n' "$H" "$H/.ssh" "$H/.ssh/authorized_keys"
cat "$SUDOF"
if su - "$U" -c id </dev/null; then echo "su -c id: PERMITIDO (MAL)"; else echo "su -c id: rechazado (bien)"; fi
if su - "$U" </dev/null; then echo "su sesion: PERMITIDA (MAL)"; else echo "su sesion: rechazada (bien)"; fi
echo "--- sshd efectivo para $U ---"
sshd -T -C user="$U",host=stick,addr="$STICK_IP" \
  | grep -iE '^(forcecommand|permittty|allowtcpforwarding|allowagentforwarding|x11forwarding|permittunnel|permituserrc|authenticationmethods) '
echo "--- root sin cambios: OK ---"
echo "LISTO: '$U' solo puede apagar Urano via SSH desde $STICK_IP."
REMOTE
