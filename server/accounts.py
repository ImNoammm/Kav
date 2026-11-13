"""Accounts on a copy of Kav's server (server/selfhost, ACCESS=accounts): who may use it, and each of their phones.

A person signs in once on a phone, with their username and password or an invite, and the phone gets a device token of
its own: its requests are signed with it as Kav's are with the app key (X-Kav), and say which device they're from
(X-Kav-Device). The password never stays on the phone; signing a phone out revokes its token alone.

Kept: the accounts (scrypt hashes), their phones and when each was last seen, invites, and wrong-password pauses.
Nothing about trips, searches or places. Python's own library only; live.py and the setup page share it."""
import hashlib
import hmac
import os
import secrets
import sqlite3
import threading
import time
from contextlib import closing

INVITE_DAYS = 7
PAUSE_AFTER = 5  # wrong passwords in a row before a username is paused
PAUSE_S = 15 * 60
MIN_PASSWORD = 8
SETUP = "setup:"  # the setup page's own count of wrong passwords, apart from the phones' (pauses)
SEEN_EVERY = 300  # seconds between the writes of a phone's "last seen"
# Passwords too common to allow, however long (the top of the usual lists).
COMMON = {"password", "12345678", "123456789", "1234567890", "qwertyuiop", "11111111", "00000000", "password1",
          "iloveyou", "qwerty123", "1q2w3e4r", "abcd1234", "kavkavkav", "princess", "sunshine", "football"}


class Refused(Exception):
    """Why a sign-in or a change wasn't made, in words for the app and the dashboard. paused: seconds left of a pause."""

    def __init__(self, why, paused=0):
        super().__init__(why)
        self.paused = paused


def hash_password(password, salt=None):
    salt = salt or secrets.token_bytes(16)
    return salt.hex() + "$" + hashlib.scrypt(password.encode(), salt=salt, n=1 << 14, r=8, p=1, dklen=32).hex()


def password_matches(password, stored):
    try:
        salt, _ = stored.split("$", 1)
        return hmac.compare_digest(hash_password(password, bytes.fromhex(salt)), stored)
    except ValueError:
        return False


def weak(password):
    """Why a password won't do, or None."""
    if len(password) < MIN_PASSWORD:
        return "A password needs at least %d characters." % MIN_PASSWORD
    if password.lower() in COMMON or len(set(password)) < 3:
        return "That password is too easy to guess."
    return None


class Store:
    def __init__(self, path):
        self.path, self.lock, self.seen = path, threading.Lock(), {}
        with self.lock, closing(self.connect()) as db, db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS people (name TEXT PRIMARY KEY, hash TEXT, admin INTEGER, disabled INTEGER,
                    created INTEGER);
                CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT, token TEXT, model TEXT,
                    created INTEGER, seen INTEGER);
                CREATE TABLE IF NOT EXISTS invites (code TEXT PRIMARY KEY, name TEXT, expires INTEGER);
                CREATE TABLE IF NOT EXISTS pauses (name TEXT PRIMARY KEY, wrong INTEGER, until INTEGER);
            """)
        os.chmod(path, 0o600)

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.execute("PRAGMA journal_mode=WAL")
        return db

    def add(self, name, password=None, admin=False):
        name = name.strip().lower()
        if not (1 <= len(name) <= 40) or not all(c.isalnum() or c in "._-" for c in name):
            raise Refused("A username is letters, numbers, dots, dashes and underscores.")
        if password is not None and weak(password):
            raise Refused(weak(password))
        with self.lock, closing(self.connect()) as db, db:
            if db.execute("SELECT 1 FROM people WHERE name = ?", (name,)).fetchone():
                raise Refused("There's already someone called %s." % name)
            db.execute("INSERT INTO people (name, hash, admin, disabled, created) VALUES (?, ?, ?, 0, ?)",
                       (name, hash_password(password) if password else "", int(admin), int(time.time())))
        return name

    def people(self):
        """Everyone, with their phones: [{name, admin, disabled, devices: [{id, model, seen}]}]."""
        with closing(self.connect()) as db:
            out = [{"name": n, "admin": bool(a), "disabled": bool(d), "devices": []}
                   for n, a, d in db.execute("SELECT name, admin, disabled FROM people ORDER BY name")]
            phones = db.execute("SELECT id, name, model, seen FROM devices ORDER BY seen DESC").fetchall()
        by = {p["name"]: p for p in out}
        for i, n, model, seen in phones:
            if n in by:
                by[n]["devices"].append({"id": i, "model": model, "seen": seen})
        return out

    def set_password(self, name, password):
        if weak(password):
            raise Refused(weak(password))
        with self.lock, closing(self.connect()) as db, db:
            db.execute("UPDATE people SET hash = ? WHERE name = ?", (hash_password(password), name))
            db.execute("DELETE FROM pauses WHERE name = ?", (name,))

    def update(self, name, admin=None, disabled=None):
        with self.lock, closing(self.connect()) as db, db:
            for column, value in (("admin", admin), ("disabled", disabled)):
                if value is not None:
                    db.execute("UPDATE people SET %s = ? WHERE name = ?" % column, (int(value), name))
            if disabled:
                db.execute("DELETE FROM devices WHERE name = ?", (name,))

    def remove(self, name):
        with self.lock, closing(self.connect()) as db, db:
            for table in ("people", "devices", "invites", "pauses"):
                db.execute("DELETE FROM %s WHERE name = ?" % table, (name,))
            db.execute("DELETE FROM pauses WHERE name = ?", (SETUP + name,))

    def verify(self, name, password, now=None, setup=False):
        """The person, for the right password: {name, admin}. Five wrong ones in a row pause the username, counted
        apart for phones (sign_in, which anyone on the internet can try) and for the setup page (setup=True, nearby
        only): wrong passwords from a phone never keep an admin out of the setup page."""
        now = int(time.time() if now is None else now)
        name = (name or "").strip().lower()
        key = SETUP + name if setup else name
        with self.lock, closing(self.connect()) as db, db:
            paused = db.execute("SELECT wrong, until FROM pauses WHERE name = ?", (key,)).fetchone()
            if paused and paused[1] > now:
                raise Refused("Too many wrong passwords. Try again in %d minutes." % -(-(paused[1] - now) // 60),
                              paused[1] - now)
            row = db.execute("SELECT hash, disabled, admin FROM people WHERE name = ?", (name,)).fetchone()
            right = bool(row and row[0] and password_matches(password or "", row[0]))
            if not right:
                # Kept before the refusal: raised inside the transaction, it would be rolled back with it.
                wrong = (paused[0] if paused else 0) + 1
                db.execute("INSERT OR REPLACE INTO pauses VALUES (?, ?, ?)",
                           (key, 0 if wrong >= PAUSE_AFTER else wrong, now + PAUSE_S if wrong >= PAUSE_AFTER else 0))
            else:
                db.execute("DELETE FROM pauses WHERE name = ?", (key,))
        if not right:
            raise Refused("Wrong username or password.")
        if row[1]:
            raise Refused("This account is turned off. Ask whoever runs the server.")
        return {"name": name, "admin": bool(row[2])}

    def sign_in(self, name, password, model="", now=None):
        """A new phone's device id and token, for the right password."""
        now = int(time.time() if now is None else now)
        person = self.verify(name, password, now)
        with self.lock, closing(self.connect()) as db, db:
            return self._device(db, person["name"], model, now)

    def has_admin(self):
        with closing(self.connect()) as db:
            return db.execute("SELECT 1 FROM people WHERE admin = 1 AND disabled = 0").fetchone() is not None

    def invite(self, name, now=None):
        """A one-time code that signs a phone in as `name`, for INVITE_DAYS (in a QR code: kav://server?…&invite=)."""
        now = int(time.time() if now is None else now)
        code = secrets.token_urlsafe(16)
        with self.lock, closing(self.connect()) as db, db:
            db.execute("DELETE FROM invites WHERE expires <= ?", (now,))
            db.execute("INSERT INTO invites VALUES (?, ?, ?)", (code, name, now + INVITE_DAYS * 86400))
        return code

    def redeem(self, code, model="", now=None):
        now = int(time.time() if now is None else now)
        with self.lock, closing(self.connect()) as db, db:
            row = db.execute("SELECT name, expires FROM invites WHERE code = ?", (code or "",)).fetchone()
            db.execute("DELETE FROM invites WHERE code = ?", (code or "",))
            if not row or row[1] <= now:
                raise Refused("This invite has expired or was already used. Ask for a new one.")
            person = db.execute("SELECT disabled FROM people WHERE name = ?", (row[0],)).fetchone()
            if not person or person[0]:
                raise Refused("This account is turned off. Ask whoever runs the server.")
            return self._device(db, row[0], model, now)

    def _device(self, db, name, model, now):
        device, token = secrets.token_urlsafe(12), secrets.token_urlsafe(24)
        db.execute("INSERT INTO devices VALUES (?, ?, ?, ?, ?, ?)", (device, name, token, (model or "")[:60], now, now))
        admin = db.execute("SELECT admin FROM people WHERE name = ?", (name,)).fetchone()[0]
        return {"device": device, "token": token, "user": name, "admin": bool(admin)}

    def sign_out(self, device):
        with self.lock, closing(self.connect()) as db, db:
            db.execute("DELETE FROM devices WHERE id = ?", (device,))

    def token(self, device, now=None):
        """The device's token while its person may use the server, else None; notes when it was last seen."""
        now = int(time.time() if now is None else now)
        with closing(self.connect()) as db:
            row = db.execute("SELECT d.token FROM devices d JOIN people p ON p.name = d.name "
                             "WHERE d.id = ? AND p.disabled = 0", (device or "",)).fetchone()
            if row and now - self.seen.get(device, 0) >= SEEN_EVERY:
                self.seen[device] = now
                with self.lock, db:
                    db.execute("UPDATE devices SET seen = ? WHERE id = ?", (now, device))
        return row[0] if row else None


def main(argv=None):
    """The accounts from the command line, for a server set up by its .env alone (no setup page), inside its live
    container:  docker compose exec -it live python3 accounts.py add NAME --admin"""
    import argparse
    import getpass
    import sys
    import urllib.parse
    path = os.environ.get("ACCOUNTS", os.path.join(os.environ.get("HISTORY") or ".", "accounts.sqlite3"))
    p = argparse.ArgumentParser(prog="accounts.py", description="The people who may use this Kav server (ACCESS=accounts).")
    sub = p.add_subparsers(dest="command", required=True)
    add = sub.add_parser("add", help="add someone (asks for their password)")
    add.add_argument("name")
    add.add_argument("--admin", action="store_true", help="may also sign in to the setup page")
    add.add_argument("--invite", action="store_true", help="no password: they sign in with an invite code instead")
    sub.add_parser("list", help="everyone, and their phones")
    for command, what in (("password", "set someone's password (asks for it)"), ("invite", "a new invite code for someone"),
                          ("remove", "remove someone and sign out their phones")):
        sub.add_parser(command, help=what).add_argument("name")
    for command, what in (("admin", "make someone an admin, or not"), ("disable", "turn someone off, or on again")):
        c = sub.add_parser(command, help=what)
        c.add_argument("name")
        c.add_argument("state", choices=("on", "off"))
    args = p.parse_args(argv)
    store = Store(path)

    def password():
        first = getpass.getpass("Password: ")
        if getpass.getpass("Again: ") != first:
            raise Refused("The passwords aren't the same.")
        return first

    def invite(name):
        code = store.invite(name)
        at = urllib.parse.urlsplit(os.environ.get("PUBLIC_URL", "")).netloc
        print("Invite code for %s (it works once, for %d days): %s" % (name, INVITE_DAYS, code))
        if at:
            print("In Kav, scan it as a QR code made from: kav://server?" + urllib.parse.urlencode(
                {"address": at, "invite": code}))
    try:
        if args.command == "add":
            name = store.add(args.name, None if args.invite else password(), admin=args.admin)
            print("Added %s%s." % (name, " (admin)" if args.admin else ""))
            if args.invite:
                invite(name)
        elif args.command == "list":
            for person in store.people():
                tags = [t for t, on in (("admin", person["admin"]), ("turned off", person["disabled"])) if on]
                print("%s%s, %d phone%s" % (person["name"], " (%s)" % ", ".join(tags) if tags else "",
                                            len(person["devices"]), "" if len(person["devices"]) == 1 else "s"))
        elif args.command == "password":
            store.set_password(args.name, password())
            print("Set. Their phones stay signed in.")
        elif args.command == "invite":
            invite(args.name)
        elif args.command == "remove":
            store.remove(args.name)
        elif args.command == "admin":
            store.update(args.name, admin=args.state == "on")
        elif args.command == "disable":
            store.update(args.name, disabled=args.state == "on")
    except Refused as e:
        sys.exit(str(e))
    except (EOFError, KeyboardInterrupt):
        sys.exit("No password typed. Run it in a terminal: docker compose exec -it live python3 accounts.py ...")


if __name__ == "__main__":
    main()
