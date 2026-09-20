#!/usr/bin/env bash
set -euo pipefail
# Usage: manage.sh install NAME BINARY CONFIG SHA256
#        manage.sh update NAME BINARY SHA256
#        manage.sh remove NAME
action=${1:-}; name=${2:-}
[[ $EUID == 0 ]] || { echo 'Run as root.' >&2; exit 1; }
[[ $name =~ ^[a-z][a-z0-9-]{0,31}$ ]] || { echo 'Invalid service name.' >&2; exit 2; }
base=/opt/kkdugi-runner/$name
conf=/etc/kkdugi-runner/$name
data=/var/lib/kkdugi-runner/$name
unit=/etc/systemd/system/$name.service
for path in /opt/kkdugi-runner /etc/kkdugi-runner /var/lib/kkdugi-runner "$base" "$conf" "$data" "$unit"; do
 [[ ! -L $path ]] || { echo 'Symlink installation paths are not allowed.' >&2; exit 1; }
done
stop_service() {
 systemctl stop "$name.service"
 [[ $(systemctl show "$name.service" --property=MainPID --value) == 0 ]] || { echo 'Service is still running.' >&2; exit 1; }
}
verify_binary() {
 local source=$1 expected=$2
 [[ -f $source && ! -L $source && $expected =~ ^[a-fA-F0-9]{64}$ ]] || exit 2
 local actual; actual=$(sha256sum -- "$source"); actual=${actual%% *}
 [[ ${actual,,} == ${expected,,} ]] || { echo 'Binary checksum mismatch.' >&2; exit 1; }
}
case $action in
 install)
  [[ $# == 5 && ! -e $unit && ! -e $base && ! -e $conf && ! -e $data ]] || { echo 'Installation already exists or arguments are invalid.' >&2; exit 1; }
  verify_binary "$3" "$5"
  [[ -f $4 && ! -L $4 ]] || exit 2
  if getent passwd "$name" >/dev/null; then echo 'Choose a new dedicated service account.' >&2; exit 1; fi
  useradd --system --user-group --home-dir "$data" --no-create-home --shell /usr/sbin/nologin "$name"
  install -d -m 0755 "$base" "$conf"
  install -d -m 0700 -o "$name" -g "$name" "$data"
  install -m 0755 "$3" "$base/runner"
  install -m 0644 "$4" "$conf/runner.toml"
  # Configuration must use this dedicated data directory and trusted absolute paths.
  runuser -u "$name" -- "$base/runner" verify --config "$conf/runner.toml"
  cat > "$unit" <<EOF
[Unit]
Description=Kkdugi runner ($name)
Wants=network-online.target
After=network-online.target
StartLimitIntervalSec=300
StartLimitBurst=3

[Service]
Type=simple
User=$name
Group=$name
WorkingDirectory=$data
ExecStart=$base/runner run --config $conf/runner.toml
Restart=on-failure
RestartSec=10
TimeoutStopSec=90
KillMode=mixed
UMask=0077
NoNewPrivileges=true
ProtectHome=true
ProtectSystem=full
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
  systemd-analyze verify "$unit"
  systemctl daemon-reload
  echo "Installed without starting. Register as $name, then systemctl enable --now $name.service"
  ;;
 update)
  [[ $# == 4 && -f $unit && -f $base/runner && ! -L $base && ! -L $conf ]] || exit 2
  verify_binary "$3" "$4"
  install -m 0755 "$3" "$base/runner.next"
  runuser -u "$name" -- "$base/runner.next" verify --config "$conf/runner.toml"
  stop_service
  cp --preserve=mode,ownership -- "$base/runner" "$base/runner.previous"
  mv -f -- "$base/runner.next" "$base/runner"
  systemctl start "$name.service"
  echo 'Updated binary. State and credential retained; inspect journal and admin readiness.'
  ;;
 remove)
  [[ $# == 2 && -f $unit ]] || exit 2
  stop_service
  systemctl disable "$name.service"
  rm -- "$unit"
  systemctl daemon-reload
  echo 'Service removed. Binary, config, account, state and credential retained.'
  ;;
 *) echo 'Usage: manage.sh install NAME BINARY CONFIG SHA256 | update NAME BINARY SHA256 | remove NAME' >&2; exit 2;;
esac
