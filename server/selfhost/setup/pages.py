"""The setup page's pages, laid out as AdGuard Home's are: a bar along the top with the server's sections, cards with
their titles in them, and the first setup as one card in the middle with its progress along its foot. Kav's colours:
black, Kav's purple, its own arrow for a logo; light where the system is. English. The server renders them whole, with
a little JavaScript for checks and live status. Everything they do goes through S, the setup service (server.py)."""
import html
import http.server
import ipaddress
import json
import os
import threading
import time
import urllib.parse

S = None  # server.py, set as it starts

STEPS = ("live", "address", "access", "parts", "review")
STEP_NAMES = ("Live buses", "Address", "Who may use it", "Parts", "Ready")
REPO = "https://github.com/ImNoammm/kav"
DOCS = "https://kavapp.link/docs/self-hosting"  # the guide; each part of this page links its own section


def esc(v):
    return html.escape(str(v), quote=True)


# Lucide's icons (lucide.dev, ISC licence), drawn in the text's own colour.
ICONS = {
    "overview": '<rect width="7" height="9" x="3" y="3" rx="1"/><rect width="7" height="5" x="14" y="3" rx="1"/>'
                '<rect width="7" height="9" x="14" y="12" rx="1"/><rect width="7" height="5" x="3" y="16" rx="1"/>',
    "parts": '<path d="M10 5H3"/><path d="M12 19H3"/><path d="M14 3v4"/><path d="M16 17v4"/><path d="M21 12h-9"/>'
             '<path d="M21 19h-5"/><path d="M21 5h-7"/><path d="M8 10v4"/><path d="M8 12H3"/>',
    "people": '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><path d="M16 3.128a4 4 0 0 1 0 7.744"/>'
              '<path d="M22 21v-2a4 4 0 0 0-3-3.87"/><circle cx="9" cy="7" r="4"/>',
    "updates": '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/>'
               '<path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
    "storage": '<path d="M10 16h.01"/><path d="M2.212 11.577a2 2 0 0 0-.212.896V18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5.527a2 2 0 '
               '0 0-.212-.896L18.55 5.11A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/><path d="M21.946 12.013H2.054"/>'
               '<path d="M6 16h.01"/>',
    "user": '<circle cx="12" cy="12" r="10"/><circle cx="12" cy="10" r="3"/><path d="M7 20.662V19a2 2 0 0 1 2-2h6a2 2 0 0 1 '
            '2 2v1.662"/>',
    "signout": '<path d="m16 17 5-5-5-5"/><path d="M21 12H9"/><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>',
    "search": '<path d="m21 21-4.34-4.34"/><circle cx="11" cy="11" r="8"/>',
    "chevron": '<path d="m6 9 6 6 6-6"/>',
    "plus": '<path d="M5 12h14"/><path d="M12 5v14"/>',
    "qr": '<rect width="5" height="5" x="3" y="3" rx="1"/><rect width="5" height="5" x="16" y="3" rx="1"/><rect width="5" '
          'height="5" x="3" y="16" rx="1"/><path d="M21 16h-3a2 2 0 0 0-2 2v3"/><path d="M21 21v.01"/><path d="M12 7v3a2 2 0 '
          '0 1-2 2H7"/><path d="M3 12h.01"/><path d="M12 3h.01"/><path d="M12 16v.01"/><path d="M16 12h1"/><path d="M21 12v.01"'
          '/><path d="M12 21v-1"/>',
    "close": '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
    "more": '<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',
    "copy": '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
    "admin": '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 '
             '6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="M6.376 18.91a6 6 0 0 1 '
             '11.249.003"/><circle cx="12" cy="11" r="4"/>',
    "help": '<circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><path d="M12 17h.01"/>',
    "lock": '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
    "build": '<path d="m15 12-9.373 9.373a1 1 0 0 1-3.001-3L12 9"/><path d="m18 15 4-4"/><path d="m21.5 11.5-1.914-1.914A2 '
             '2 0 0 1 19 8.172v-.344a2 2 0 0 0-.586-1.414l-1.657-1.657A6 6 0 0 0 12.516 3H9l1.243 1.243A6 6 0 0 1 12 8.485V10l2 '
             '2h1.172a2 2 0 0 1 1.414.586L18.5 14.5"/>',
}
GITHUB = ('<svg class="i" viewBox="0 0 24 24" aria-hidden="true" fill="currentColor"><path d="M12 .3a12 12 0 0 0-3.8 23.4c.6.1'
          '.8-.3.8-.6v-2.2c-3.3.7-4-1.4-4-1.4-.5-1.4-1.3-1.7-1.3-1.7-1.1-.8.1-.8.1-.8 1.2.1 1.8 1.3 1.8 1.3 1 1.8 2.8 1.3 3.4 1 '
          '.1-.8.4-1.3.8-1.6-2.7-.3-5.5-1.3-5.5-6a4.7 4.7 0 0 1 1.2-3.2c-.1-.3-.5-1.6.1-3.2 0 0 1-.3 3.3 1.2a11.5 11.5 0 0 1 6 '
          '0C17 5.1 18 5.4 18 5.4c.6 1.6.2 2.9.1 3.2a4.7 4.7 0 0 1 1.2 3.2c0 4.7-2.8 5.7-5.5 6 .4.4.8 1.1.8 2.2v3.1c0 .3.2.7.8'
          '.6A12 12 0 0 0 12 .3z"/></svg>')
# Kav's app icon: its arrow (ic_launcher_foreground.xml) on its black.
LOGO = ('<svg class="logo" viewBox="0 0 108 108" aria-hidden="true"><defs><linearGradient id="lg" x1="0" y1="0" x2="0" '
        'y2="1"><stop offset="0" stop-color="#444"/><stop offset=".69" stop-color="#000"/></linearGradient></defs>'
        '<rect width="108" height="108" rx="24" fill="url(#lg)"/><path fill="#fff" d="M52.47,33.55 A1.73,1.73 0 0,1 55.53,'
        '33.55 L76.76,74.34 A0.95,0.95 0 0,1 75.59,75.67 L54,67.68 L32.41,75.67 A0.95,0.95 0 0,1 31.24,74.34 Z"/></svg>')


def icon(name):
    return ('<svg class="i" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" '
            'stroke-linecap="round" stroke-linejoin="round">%s</svg>') % ICONS[name]


CSS = """
:root{--page:#000;--ink:#f5f5f7;--muted:#a1a1a6;--faint:#86868b;--card:#1c1c1e;--edge:#2c2c2e;--field:#000;
--hover:#2c2c2e;--accent:#bf5af2;--button:#a24ad8;--button-hover:#9341c9;--accent-soft:rgba(191,90,242,.16);
--on-accent:#fff;--good:#30d158;--bad:#ff6961;--warn:#ffd60a;--tint-good:#30d1581a;--tint-bad:#ff69611a;
--tint-warn:#ffd60a14;--radius:14px;--ease:cubic-bezier(.2,.8,.2,1)}
@media (prefers-color-scheme:light){:root{--page:#f5f5f7;--ink:#1d1d1f;--muted:#6e6e73;--faint:#86868b;--card:#fff;
--edge:#e5e5ea;--field:#fff;--hover:#f0f0f2;--accent:#8e3cc4;--button:#8e3cc4;--button-hover:#7a2fb0;
--accent-soft:rgba(142,60,196,.12);--good:#1f8a4c;--bad:#c23b45;--warn:#946200;--tint-good:#1f8a4c14;
--tint-bad:#c23b4512;--tint-warn:#94620014}}
*{box-sizing:border-box}
:root{color-scheme:dark}html{background:var(--page)}
@media (prefers-color-scheme:light){:root{color-scheme:light}}
body{margin:0;min-height:100vh;display:flex;flex-direction:column;background:var(--page);color:var(--ink);
font:16px/1.5 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",system-ui,Roboto,"Helvetica Neue",Arial,sans-serif;
-webkit-font-smoothing:antialiased}
a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline;text-underline-offset:3px}
.i{width:16px;height:16px;flex:none}.logo{width:28px;height:28px;flex:none;border-radius:7px;box-shadow:0 0 0 1px var(--edge)}
.wrap{width:100%;max-width:1120px;margin:0 auto;padding:0 20px}

.bar{background:color-mix(in srgb,var(--page) 82%,transparent);-webkit-backdrop-filter:saturate(180%) blur(20px);
backdrop-filter:saturate(180%) blur(20px);border-bottom:1px solid var(--edge);position:sticky;top:0;z-index:2}
.bar .wrap{display:flex;align-items:center;gap:28px;min-height:64px}.brand:hover{text-decoration:none}
.brand{display:flex;align-items:center;gap:10px;color:var(--ink);text-decoration:none;font-weight:700;font-size:17px}
.tabs{display:flex;gap:4px;align-self:stretch;overflow-x:auto;scrollbar-width:none}
.tabs{align-items:center}
.tabs a{display:flex;align-items:center;gap:7px;padding:7px 13px;border-radius:999px;color:var(--muted);text-decoration:none;
font-size:15px;font-weight:500;white-space:nowrap;transition:color .2s var(--ease),background-color .2s var(--ease)}
.tabs a:hover{color:var(--ink);text-decoration:none}.tabs a.on{color:var(--accent);background:var(--accent-soft)}
.account{margin-left:auto;display:flex;align-items:center;gap:6px;color:var(--muted);font-size:14px}
.account .name{display:flex;align-items:center;gap:7px;padding:0 6px;color:var(--ink);font-weight:500}.account .name .i{color:var(--accent)}
.icon-button{display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:999px;
border:1px solid var(--edge);background:none;color:var(--faint);cursor:pointer;padding:0;transition:color .15s,background .15s}
.icon-button:hover{color:var(--ink);background:var(--hover)}

main{flex:1;padding:30px 0 48px}
.head{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:20px}
h1{font-size:28px;line-height:1.2;letter-spacing:-.02em;font-weight:700;margin:0}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:18px;margin-bottom:18px}
.stats:empty{display:none}
.stat{background:var(--card);border:1px solid var(--edge);border-radius:var(--radius);padding:18px 22px}
.stat b{display:block;font-size:28px;font-weight:700;letter-spacing:-.02em;margin-top:2px;font-variant-numeric:tabular-nums}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:18px;align-items:start}
.grid>.card{margin:0}
.card{background:var(--card);border:1px solid var(--edge);border-radius:var(--radius);margin:0 0 18px;overflow:hidden}
.card-head{display:flex;align-items:center;gap:9px;padding:15px 20px;border-bottom:1px solid var(--edge);font-weight:600;
font-size:17px;letter-spacing:-.01em}
.card-head .i{color:var(--faint)}
.learn{display:inline-flex;align-items:center;gap:5px;font-size:13px;font-weight:500;color:var(--faint);text-decoration:none;
white-space:nowrap;transition:color .15s}.learn:hover{color:var(--accent)}.learn .i{width:14px;height:14px}
.card-head .learn{margin-left:auto}.head .learn{margin-left:auto}
.card-body{padding:16px 20px}
p{margin:6px 0}.note{color:var(--faint);font-size:14px}.good{color:var(--good)}.bad{color:var(--bad)}.warn{color:var(--warn)}
.alert{border-radius:10px;padding:12px 16px;margin:0 0 18px;border:1px solid}
.alert.good{background:var(--tint-good);border-color:var(--good);color:var(--ink)}
.alert.bad{background:var(--tint-bad);border-color:var(--bad);color:var(--ink)}
.alert.warn{background:var(--tint-warn);border-color:var(--warn);color:var(--ink)}

label.f{display:block;margin:14px 0 6px;color:var(--muted);font-size:14px;font-weight:500}
.card-body>label.f:first-of-type,.card-body>form>label.f:first-of-type{margin-top:0}
input[type=text],input[type=password],input[type=url],input[type=search],select{width:100%;padding:11px 14px;border-radius:10px;
border:1px solid var(--edge);background:var(--field);color:var(--ink);font:inherit}
input[type=text]:focus,input[type=password]:focus,input[type=url]:focus,input[type=search]:focus,select:focus{
outline:2px solid var(--accent);outline-offset:-1px;border-color:transparent}
input[type=radio],input[type=checkbox]{appearance:none;-webkit-appearance:none;margin:0;flex:none;width:18px;height:18px;
border:1.5px solid var(--faint);background:var(--field);display:inline-grid;place-content:center;cursor:pointer;
transition:border-color .2s,background-color .2s}
input[type=radio]{border-radius:50%}input[type=checkbox]{border-radius:5px}
input[type=radio]::before{content:"";width:8px;height:8px;border-radius:50%;background:var(--on-accent);transform:scale(0);
transition:transform .22s cubic-bezier(.3,1.6,.5,1)}
input[type=checkbox]::before{content:"";width:9px;height:5px;margin-top:-3px;border:2px solid var(--on-accent);border-top:0;
border-right:0;transform:rotate(-45deg) scale(0);transition:transform .22s cubic-bezier(.3,1.6,.5,1)}
input[type=radio]:checked,input[type=checkbox]:checked{border-color:var(--accent);background:var(--accent)}
input[type=radio]:checked::before{transform:scale(1)}input[type=checkbox]:checked::before{transform:rotate(-45deg) scale(1)}
input[type=radio]:focus-visible,input[type=checkbox]:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.opt:hover input:not(:checked){border-color:var(--muted)}
.opt{display:flex;gap:12px;align-items:flex-start;padding:13px 0;border-top:1px solid var(--edge);cursor:pointer}
.card-body>.opt:first-of-type,.card-body>form>.opt:first-of-type{border-top:0;padding-top:2px}
.opt input{margin-top:2px}
.opt b{display:block;font-weight:600}.opt .note{display:block}
.fold{display:grid;grid-template-rows:0fr;opacity:0;transition:grid-template-rows .32s cubic-bezier(.2,.8,.2,1),opacity .22s}
.fold>div{overflow:hidden;min-height:0;visibility:hidden;transition:visibility 0s .32s}
.opt:has(input:checked)+.more,.more:has(option[value=custom]:checked) .custom,.keyed,.whole,.english{
grid-template-rows:1fr;opacity:1}
.opt:has(input:checked)+.more>div,.more:has(option[value=custom]:checked) .custom>div,.keyed>div,.whole>div,.english>div{
visibility:visible;transition:visibility 0s}
.card:has(input[name=live][value=none]:checked) .keyed,.card:has(select[name=where] option[value=all]:not(:checked)) .whole,
.card:has(input[name=alerts][value=off]:checked) .english{
grid-template-rows:0fr;opacity:0}
.card:has(input[name=live][value=none]:checked) .keyed>div,.card:has(select[name=where] option[value=all]:not(:checked)) .whole>div,
.card:has(input[name=alerts][value=off]:checked) .english>div{
visibility:hidden;transition:visibility 0s .32s}
.more>div>.fold-in{padding:0 0 14px 30px}.more>div>.fold-in>p:first-child{margin-top:-4px}.fold-in>label.f:first-child{margin-top:0}
.more .opt{border-top:0;padding-bottom:0}.whole .fold-in{padding-bottom:4px}.custom .fold-in{padding-top:12px}

button,.button{display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:40px;padding:0 20px;
border-radius:999px;border:1px solid var(--button);background:var(--button);color:var(--on-accent);
font:600 15px/1 -apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",system-ui,sans-serif;cursor:pointer;
text-decoration:none;transition:background-color .2s var(--ease),border-color .2s var(--ease),transform .2s var(--ease)}
button:hover,.button:hover{background:var(--button-hover);border-color:var(--button-hover);text-decoration:none}
button:active,.button:active{transform:scale(.98)}
button:focus-visible,.button:focus-visible,.tabs a:focus-visible,.learn:focus-visible{outline:3px solid var(--accent);outline-offset:3px}
button.quiet,.button.quiet{background:none;color:var(--ink);border-color:var(--edge)}
button.quiet:hover,.button.quiet:hover{background:var(--hover);border-color:var(--edge)}
button.danger-fill{background:var(--bad);border-color:var(--bad);color:#fff}
button.danger{background:none;color:var(--bad);border-color:var(--edge)}button.danger:hover{background:var(--tint-bad);border-color:var(--edge)}
button.small,.button.small{min-height:34px;padding:0 15px;font-size:14px}
.actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:16px}
.row{display:flex;align-items:center;gap:12px;padding:11px 0;border-top:1px solid var(--edge)}
.card-body>.row:first-child,#status>.row:first-child{border-top:0;padding-top:2px}
.row .grow{flex:1;min-width:0}.row b{font-weight:600;text-align:right}
.dot{width:8px;height:8px;border-radius:50%;flex:none;background:var(--faint);align-self:flex-start;margin-top:7.5px}
.dot.good{background:var(--good);box-shadow:0 0 0 3px var(--tint-good)}.dot.bad{background:var(--bad);box-shadow:0 0 0 3px var(--tint-bad)}
.dot.warn{background:var(--warn);box-shadow:0 0 0 3px var(--tint-warn)}
.tag{display:inline-block;padding:1px 8px;border-radius:999px;font-size:12px;font-weight:600;background:var(--hover);
color:var(--muted);margin-left:6px;vertical-align:1px}
pre{white-space:pre-wrap;background:var(--field);border:1px solid var(--edge);border-radius:8px;padding:12px;font-size:13px;
color:var(--muted);max-height:320px;overflow:auto}
.qr{background:#fff;border-radius:12px;padding:10px;display:inline-block;margin:10px 0;animation:rise .4s cubic-bezier(.2,.8,.2,1) both}
.qr svg{display:block;width:200px;height:200px}
.meter{height:6px;border-radius:3px;background:var(--hover);overflow:hidden;margin:10px 0}
.meter i{display:block;height:100%;background:var(--accent);transition:width .3s}.meter i.over{background:var(--bad)}
details summary{cursor:pointer;color:var(--muted);margin:12px 0 4px;font-weight:500;list-style:none;display:flex;
align-items:center;gap:6px;width:fit-content}
details summary::-webkit-details-marker{display:none}
details summary::before{content:"";width:6px;height:6px;border:solid currentColor;border-width:0 1.5px 1.5px 0;
transform:rotate(-45deg);transition:transform .25s cubic-bezier(.2,.8,.2,1);margin:0 3px 0 1px}
details[open]:not(.closing) summary::before{transform:rotate(45deg)}details summary:hover{color:var(--ink)}
code{font:13px ui-monospace,SFMono-Regular,Menlo,monospace;background:var(--hover);padding:2px 6px;border-radius:6px}

.people{overflow:visible}
.people-tools{display:flex;gap:10px;align-items:center;padding:14px 20px;border-bottom:1px solid var(--edge)}
.search{position:relative;flex:1;max-width:360px}.search .i{position:absolute;left:11px;top:50%;margin-top:-8px;color:var(--faint)}
.search input{padding-left:34px}.people-tools .add{margin-left:auto}
.ptable{display:grid;grid-template-columns:minmax(0,2.4fr) minmax(0,1.1fr) minmax(0,1fr) minmax(0,1.1fr) 104px}
.prow{display:contents}.prow>*{padding:12px 0 12px 20px;border-top:1px solid var(--edge);display:flex;align-items:center;min-width:0}
.prow>:last-child{padding-right:16px;justify-content:flex-end;gap:6px}
.prow.headings>*{border-top:0;padding-top:10px;padding-bottom:8px;font-size:12px;font-weight:600;color:var(--faint);
text-transform:uppercase;letter-spacing:.04em}
.prow.hidden{display:none}.prow.changed>*{animation:changed 1.2s ease-out}
@keyframes changed{from{background:color-mix(in srgb,var(--accent) 16%,transparent)}}.prow:not(.headings):hover>*{background:color-mix(in srgb,var(--hover) 45%,transparent)}
.prow:last-child>:first-child{border-bottom-left-radius:12px}.prow:last-child>:last-child{border-bottom-right-radius:12px}
.who{gap:12px}.who b{font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.who .sub{display:none}
.avatar{width:32px;height:32px;border-radius:50%;background:var(--hover);display:grid;place-content:center;font-weight:700;
color:var(--muted);flex:none;text-transform:uppercase;font-size:14px}
.role{display:inline-block;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:600;background:var(--hover);color:var(--muted)}
.role.admin{background:var(--accent-soft);color:var(--accent)}
.role.off{color:var(--faint)}
.cell-note{color:var(--faint);font-size:14px}
.nobody{grid-column:1/-1;padding:18px 20px;color:var(--faint);display:none;border-top:1px solid var(--edge)}
.ptable:not(:has(.prow.person:not(.hidden))) .nobody{display:block}
.menu-wrap{position:relative}
.menu{position:absolute;right:0;top:calc(100% + 6px);min-width:210px;background:var(--card);border:1px solid var(--edge);
border-radius:10px;box-shadow:0 14px 36px #0007;padding:6px;z-index:5;opacity:0;transform:translateY(-4px) scale(.97);
transform-origin:top right;pointer-events:none;transition:opacity .14s,transform .14s}
.menu.open{opacity:1;transform:none;pointer-events:auto}.menu form{margin:0}
.menu hr{border:0;border-top:1px solid var(--edge);margin:6px 0}
button.item{display:flex;width:100%;min-height:34px;padding:0 10px;border:0;border-radius:6px;background:none;color:var(--ink);
font:500 14px/1 system-ui,-apple-system,sans-serif;justify-content:flex-start;gap:8px}
button.item:hover,button.item:focus-visible{background:var(--hover);filter:none;outline:0}button.item.danger{color:var(--bad)}
button.item:active{transform:none}

dialog.sheet{width:min(440px,calc(100% - 32px));max-height:calc(100% - 48px);padding:0;border:1px solid var(--edge);
border-radius:20px;background:var(--card);color:var(--ink);box-shadow:0 24px 70px #000a;overflow:auto}
dialog.sheet[open]{animation:pop .24s cubic-bezier(.2,.9,.25,1.05)}dialog.sheet.out{animation:pop-out .16s ease-in forwards}
dialog.sheet::backdrop{background:#000a;backdrop-filter:blur(3px)}
dialog.sheet[open]::backdrop{animation:fade .24s}dialog.sheet.out::backdrop{animation:fade-out .16s forwards}
@keyframes pop{from{opacity:0;transform:translateY(10px) scale(.97)}to{opacity:1;transform:none}}
@keyframes pop-out{to{opacity:0;transform:translateY(6px) scale(.98)}}@keyframes fade-out{to{opacity:0}}
.sheet-head{display:flex;align-items:center;gap:12px;padding:18px 20px 6px}.sheet-head h2{font-size:18px;margin:0;flex:1}
.sheet-head .icon-button{border:0;width:30px;height:30px}
.sheet-body{padding:6px 20px 4px}.sheet-body>p:first-child{margin-top:0;color:var(--muted)}
.sheet-foot{display:flex;justify-content:flex-end;gap:8px;padding:16px 20px 20px}
.sheet .qr{display:block;width:fit-content;margin:14px auto 12px}.sheet .qr svg{width:220px;height:220px}
.copy{display:flex;gap:8px;margin-top:6px}.copy input{font:12px ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--muted)}
.copy button{flex:none}

@keyframes rise{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
@keyframes fade{from{opacity:0}to{opacity:1}}
html:not(.settled) :is(main .head,main .stats>*,main .grid>*,main .wrap>.card,main .wrap>form>.card,.setup,main.solo>.brand){
animation:rise .42s cubic-bezier(.2,.8,.2,1) both}
html:not(.settled) :is(.stats>:nth-child(2),.grid>:nth-child(2),.wrap>form>.card:nth-of-type(2)){animation-delay:.04s}
html:not(.settled) :is(.stats>:nth-child(3),.grid>:nth-child(3),.wrap>form>.card:nth-of-type(3)){animation-delay:.08s}
html:not(.settled) :is(.stats>:nth-child(4),.grid>:nth-child(4),.wrap>form>.card:nth-of-type(4)){animation-delay:.12s}
html:not(.settled) :is(.grid>:nth-child(n+5),.wrap>form>.card:nth-of-type(n+5)){animation-delay:.16s}
.alert{animation:fade .3s both}
@media (prefers-reduced-motion:reduce){*,*::before,*::after{animation-duration:.01ms!important;animation-delay:0s!important;
transition-duration:.01ms!important}}

footer{border-top:1px solid var(--edge);color:var(--faint);font-size:13px}
footer .wrap{display:flex;align-items:center;gap:18px;flex-wrap:wrap;min-height:56px}
footer a{color:var(--faint);text-decoration:none;display:inline-flex;align-items:center;gap:6px}footer a:hover{color:var(--ink)}
footer .end{margin-left:auto}

main.solo{display:flex;flex-direction:column;align-items:center;padding:56px 16px 48px}
main.solo>.brand{margin-bottom:26px;font-size:20px}main.solo>.brand .logo{width:38px;height:38px;border-radius:9px}
.setup{width:100%;max-width:640px;background:var(--card);border:1px solid var(--edge);border-radius:20px;overflow:hidden}
.setup-body{padding:34px 36px 30px}
.setup h1{text-align:center;margin-bottom:6px}.setup .lead{text-align:center;color:var(--muted);font-size:17px;margin:0 0 28px;text-wrap:balance}
.setup .card{background:none;border:0;border-radius:0;margin:0 0 24px;overflow:visible}
.setup .card-head{padding:0 0 8px;border:0;font-size:16px}.setup .card-head .i{display:none}.setup .card-body{padding:0}
.setup .nav{justify-content:center;margin-top:30px}.setup .nav button{min-width:132px;min-height:48px;font-size:17px}
.setup .alert{margin-bottom:22px}
.progress{padding:14px 0 0;text-align:center;font-size:13px;color:var(--faint);border-top:1px solid var(--edge)}
.progress .track{height:4px;background:var(--hover);margin-top:14px}
.progress .track i{display:block;height:100%;background:var(--accent);transition:width .6s ease}

@media (max-width:760px){
.bar .wrap{flex-wrap:wrap;gap:0 12px;padding-top:8px}
.tabs{order:3;width:calc(100% + 40px);margin:0 -20px;padding:0 12px;min-height:46px}
.tabs a{padding:0 10px}
main{padding:22px 0 36px}.grid{grid-template-columns:1fr}
.stats{grid-template-columns:1fr 1fr;gap:12px}.stat{padding:14px 16px}.stat b{font-size:21px}
.tabs{-webkit-mask-image:linear-gradient(90deg,#000 80%,transparent);mask-image:linear-gradient(90deg,#000 80%,transparent)}
main.solo{padding:28px 12px}.setup-body{padding:26px 20px 24px}
.search{max-width:none}
.ptable{grid-template-columns:minmax(0,1fr) 96px}.prow>:nth-child(2),.prow>:nth-child(3),.prow>:nth-child(4){display:none}
.prow>*{padding-left:16px}.who .sub{display:block;font-size:13px;color:var(--faint)}.who .name{min-width:0}
footer .end{margin-left:0}}
"""

JS = """
// Run once per page load; everything element-bound runs again on content htmx puts in (htmx.onLoad).
if(!window.kav){window.kav=1;
const root=document.documentElement;let path=location.pathname;
// The first page fades in; after that, in-place updates stay still (no re-animating cards, no flash).
document.addEventListener('htmx:beforeSwap',()=>root.classList.add('settled'));
document.addEventListener('htmx:afterSettle',()=>{if(location.pathname!==path){scrollTo(0,0);path=location.pathname}});
document.addEventListener('click',e=>{document.querySelectorAll('.menu.open').forEach(m=>{
  if(!m.parentElement.contains(e.target))shut(m)})});
document.addEventListener('keydown',e=>{if(e.key==='Escape')document.querySelectorAll('.menu.open').forEach(shut)});
setInterval(()=>['status','stats'].forEach(id=>{const box=document.getElementById(id);if(!box)return;
  fetch('/fragment/'+id).then(r=>r.ok?r.text():null).then(h=>{if(h===null||h===box.innerHTML)return;
    box.innerHTML=h;box.animate({opacity:[.4,1]},{duration:300,easing:'ease-out'})})}),15000);
if(window.qrcode)qrcode.stringToBytes=qrcode.stringToBytesFuncs['UTF-8'];
htmx.onLoad(init)}
function csrf(){return document.querySelector('main').dataset.csrf}
function shut(m){m.classList.remove('open');const b=m.parentElement.querySelector('[data-menu]');if(b)b.setAttribute('aria-expanded','false')}
function show(d){if(d.open)return;d.classList.remove('out');d.showModal();const f=d.querySelector('input:not([type=hidden]):not([readonly])');
  if(f)setTimeout(()=>f.focus(),60)}
function hide(d){if(!d.open||d.classList.contains('out'))return;d.classList.add('out');
  d.addEventListener('animationend',()=>{d.classList.remove('out');d.close()},{once:true})}
function init(el){
if(el.classList&&el.classList.contains('prow'))el.classList.add('changed');  // a row an action just updated
el.querySelectorAll('[data-test-live]').forEach(b=>b.addEventListener('click',e=>{
  e.preventDefault();const f=b.form,out=document.getElementById('live-said');out.textContent=b.dataset.wait;out.className='note';
  fetch('/api/test-live',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF':csrf()},
    body:JSON.stringify({live:(f.live||{}).value,MOT_KEY:f.MOT_KEY.value,LIVE_URL:f.LIVE_URL.value})})
  .then(r=>r.json()).then(j=>{out.textContent=j.said;out.className=j.ok?'good':'bad'})
  .catch(()=>{out.textContent='?';out.className='bad'})}));
// <details> (What's new, the build steps) open and close by height.
el.querySelectorAll('details').forEach(d=>{const s=d.querySelector('summary');s.addEventListener('click',e=>{
  e.preventDefault();if(d.dataset.busy)return;d.dataset.busy=1;const from=d.offsetHeight;
  if(d.open){d.classList.add('closing');go(from,s.offsetHeight,()=>{d.open=false;d.classList.remove('closing')})}
  else{d.open=true;go(from,d.offsetHeight)}
  function go(a,b,done){d.style.overflow='hidden';d.animate({height:[a+'px',b+'px']},{duration:280,
    easing:'cubic-bezier(.2,.8,.2,1)'}).onfinish=()=>{d.style.overflow='';delete d.dataset.busy;done&&done()}}})});
// Popups: a button names one (data-dialog) and what to fill into it (data-name); Esc, the backdrop or Cancel close it.
el.querySelectorAll('[data-dialog]').forEach(b=>b.addEventListener('click',()=>{const d=document.getElementById(b.dataset.dialog);
  if(!d)return;document.querySelectorAll('.menu.open').forEach(shut);
  d.querySelectorAll('[data-fill]').forEach(x=>{const v=b.dataset[x.dataset.fill]||'';if('value' in x&&x.tagName==='INPUT')x.value=v;
    else x.textContent=v});show(d)}));
el.querySelectorAll('dialog.sheet').forEach(d=>{d.addEventListener('cancel',e=>{e.preventDefault();hide(d)});
  d.addEventListener('click',e=>{if(e.target===d)hide(d)});
  d.querySelectorAll('[data-close]').forEach(b=>b.addEventListener('click',()=>hide(d)));
  if(d.hasAttribute('data-autoshow'))show(d)});
el.querySelectorAll('[data-menu]').forEach(b=>b.addEventListener('click',()=>{const m=b.parentElement.querySelector('.menu'),
  on=!m.classList.contains('open');document.querySelectorAll('.menu.open').forEach(shut);if(on){m.classList.add('open');
  b.setAttribute('aria-expanded','true')}}));
el.querySelectorAll('[data-search]').forEach(i=>i.addEventListener('input',()=>{const q=i.value.trim().toLowerCase();
  document.querySelectorAll('.prow.person').forEach(r=>r.classList.toggle('hidden',!r.dataset.name.includes(q)))}));
if(window.qrcode)el.querySelectorAll('[data-qr]').forEach(x=>{const q=qrcode(0,'M');q.addData(x.dataset.qr);q.make();
  x.innerHTML=q.createSvgTag({cellSize:4,margin:2,scalable:true})});
el.querySelectorAll('[data-copy]').forEach(b=>b.addEventListener('click',()=>{const f=b.parentElement.querySelector('input');
  const done=()=>{const was=b.innerHTML;b.textContent='Copied';setTimeout(()=>b.innerHTML=was,1400)};
  if(navigator.clipboard&&isSecureContext)navigator.clipboard.writeText(f.value).then(done);
  else{f.select();document.execCommand('copy');done()}}));
const mem=el.querySelector('#memory');
if(mem){const f=mem.closest('form');f.addEventListener('change',e=>{
  if(!['planner','official','predictor','where','alerts','english'].includes(e.target.name))return;
  const q=new URLSearchParams({planner:(f.planner||{}).value||'',predictor:(f.predictor||{}).value||'',
    official:f.official&&f.official.checked?'on':'',where:(f.where||{}).value||'',alerts:(f.alerts||{}).value||'',
    english:f.english&&f.english.checked?'on':''});
  const n=mem.dataset.n=(+mem.dataset.n||0)+1;  // a later choice's answer wins, whichever arrives first
  fetch('/fragment/memory?'+q).then(r=>r.ok?r.text():null).then(h=>{if(!h||n!=mem.dataset.n)return;
    const bar=mem.querySelector('.meter i'),was=bar&&bar.style.width;mem.innerHTML=h;const now=mem.querySelector('.meter i');
    if(was&&now){const to=now.style.width;now.style.width=was;requestAnimationFrame(()=>requestAnimationFrame(()=>now.style.width=to))}})})}
}
"""

HTMX = json.dumps({"allowEval": False, "historyCacheSize": 0, "refreshOnHistoryMiss": True, "scrollIntoViewOnBoost": False,
                   "globalViewTransitions": True})
STATIC = {"htmx.min.js", "qrcode.js"}
TABS = (("/", "overview", "Overview"), ("/parts", "parts", "Parts"), ("/people", "people", "People"),
        ("/updates", "updates", "Updates"), ("/storage", "storage", "Storage"))


def document(title, body, top="", solo=False, csrf=""):
    version = S.version()
    foot = ('<footer><div class="wrap"><a href="%s" rel="noreferrer">%s GitHub</a><a href="%s" rel="noreferrer">Guide</a>'
            '<a href="%s/issues" rel="noreferrer">Report an issue</a><span class="end">Kav server%s</span></div></footer>') % (
        REPO, GITHUB, DOCS, REPO, " " + esc(version) if version else "")
    # htmx (static/htmx.min.js) turns this page's own links and forms into in-place updates: no reload, no flash.
    return ("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width,"
            "initial-scale=1'><meta name=robots content=noindex><meta name=color-scheme content='dark light'>"
            "<meta name=htmx-config content='%s'><title>%s · Kav server</title><style>%s</style>"
            "<script src='/static/htmx.min.js'></script><script src='/static/qrcode.js' defer></script></head>"
            "<body hx-boost='true'>%s<main class='%s' data-csrf='%s'>%s</main>%s<script>%s</script></body></html>") % (
        HTMX, esc(title), CSS, top, "solo" if solo else "", esc(csrf), body, foot, JS)


def bar(who, csrf, nav=None):
    """The bar along the top: Kav, the sections, and who's signed in with a way out."""
    tabs = '<nav class="tabs">%s</nav>' % "".join(
        '<a href="%s"%s>%s%s</a>' % (h, ' class="on" aria-current="page"' if h == nav else "", icon(i), n)
        for h, i, n in TABS) if nav else ""
    return ('<header class="bar"><div class="wrap"><a class="brand" href="/">%s<span>Kav server</span></a>%s'
            '<div class="account"><span class="name" title="Signed in as %s, an admin of this server">%s%s</span>'
            '<form method="post" action="/signout">%s<button class="icon-button" title="Sign out" aria-label="Sign out">%s'
            '</button></form></div></div></header>') % (LOGO, tabs, esc(who), icon("admin"), esc(who), hidden(csrf), icon("signout"))


def layout(title, body, nav=None, who=None, csrf=""):
    """A page of the dashboard, under the bar."""
    return document(title, '<div class="wrap">%s</div>' % body, bar(who, csrf, nav) if who else "", csrf=csrf)


def solo(title, body, who=None, csrf="", progress=None):
    """A page that's one card in the middle, as the first setup and signing in are; the setup's progress along its foot."""
    foot = ""
    if progress is not None:
        n = progress
        foot = '<div class="progress">Step %d of %d · %s<div class="track"><i style="width:%.1f%%"></i></div></div>' % (
            n + 1, len(STEPS), esc(STEP_NAMES[n]), (n + 1) / len(STEPS) * 100)
    inner = '<div class="setup"><div class="setup-body">%s</div>%s</div>' % (body, foot)
    if who:
        return document(title, inner, bar(who, csrf), solo=True, csrf=csrf)
    return document(title, '<a class="brand" href="/">%s<span>Kav server</span></a>%s' % (LOGO, inner), solo=True, csrf=csrf)


def edited_alert(st):
    """When .env was changed by hand after this page wrote it: which lines, before Save replaces them."""
    edits = S.hand_edits(st["settings"]) if st.get("done") else []
    return alert("warn", ".env was changed by hand: %s. Saving on Parts writes what's chosen there instead. %s" % (
        ", ".join(edits), learn("every-setting-in-env"))) if edits else ""


def alert(kind, text):
    return '<div class="alert %s" role="%s">%s</div>' % (kind, "alert" if kind == "bad" else "status", text)


def page_head(title, actions=""):
    return '<div class="head"><h1>%s</h1>%s</div>' % (esc(title), actions)


def area_name(area):
    """An area as the page names it: one of S.AREAS by its name, else its box."""
    names = {"gush-dan": "Gush Dan", "jerusalem": "Jerusalem",
             "haifa": "Haifa and the Krayot", "beersheba": "Beersheba"}
    return next((names[k] for k, v in S.AREAS.items() if v == area and k in names), area)


def scan_path():
    """Where a phone scans the server's code: Kav's own names for the way there (Settings.kt)."""
    return "Settings › Live times and planners › My own › Scan the server's QR code"


def learn(anchor, label="Learn more"):
    """A link to a section of the guide (kavapp.link), opened beside this page."""
    return '<a class="learn" href="%s#%s" target="_blank" rel="noreferrer" hx-boost="false">%s%s</a>' % (
        DOCS, anchor, icon("help"), label)


def card(inner, title="", help=None):
    return '<section class="card">%s<div class="card-body">%s</div></section>' % (
        '<div class="card-head">%s%s</div>' % (esc(title), learn(help) if help else "") if title else "", inner)


def field(name, label, value="", kind="text", placeholder="", hint="", fid=None):
    return ('<label class="f" for="%s">%s</label><input type="%s" id="%s" name="%s" value="%s" placeholder="%s" '
            'autocomplete="off">%s') % (fid or name, esc(label), kind, fid or name, name, esc(value), esc(placeholder),
                                         '<p class="note">%s</p>' % hint if hint else "")


def fold(inner, cls=""):
    """Something that unfolds in place, animated (CSS: .fold), by a rule for its class."""
    return '<div class="fold %s"><div><div class="fold-in">%s</div></div></div>' % (cls, inner)


def option(name, value, label, current, note="", more="", disabled=False):
    return ('<label class="opt"><input type="radio" name="%s" value="%s"%s%s><span><b>%s</b>%s</span></label>%s') % (
        name, value, " checked" if current == value else "", " disabled" if disabled else "", esc(label),
        '<span class="note">%s</span>' % note if note else "", fold(more, "more") if more else "")


def check(name, label, on, note=""):
    return ('<label class="opt"><input type="checkbox" name="%s"%s><span><b>%s</b>%s</span></label>') % (
        name, " checked" if on else "", esc(label), '<span class="note">%s</span>' % note if note else "")


def hidden(csrf, **more):
    return '<input type="hidden" name="csrf" value="%s">' % esc(csrf) + "".join(
        '<input type="hidden" name="%s" value="%s">' % (k, esc(v)) for k, v in more.items())


def sheet(sid, title, body, foot, action=None, fields="", autoshow=False, wide=False):
    """A popup over the page (<dialog>), for one short task: a title, its content, Cancel and the action. Opened by a button
    with data-dialog=sid (JS), or as the page loads (autoshow)."""
    inner = ('<div class="sheet-head"><h2>%s</h2><button type="button" class="icon-button" data-close title="Close" '
             'aria-label="Close">%s</button></div><div class="sheet-body">%s</div><div class="sheet-foot">%s</div>') % (
        title, icon("close"), body, foot)
    if action:
        inner = '<form method=post action="%s">%s%s</form>' % (action, fields, inner)
    return '<dialog class="sheet" id="%s"%s aria-labelledby="%s-title">%s</dialog>' % (
        sid, " data-autoshow" if autoshow else "", sid, inner.replace("<h2>", '<h2 id="%s-title">' % sid, 1))


def invite_sheet(name, link, open_access, title=None, lead=None):
    """The code a phone scans to use this server: an invite for one person, or, open to anyone, just its address."""
    title = title or ("Connect a phone" if open_access else "Invite %s" % esc(name))
    lead = lead or "In Kav, open %s." % scan_path()
    after = "Anyone with this code can use the server." if open_access else "It works once, for 7 days."
    body = ('<p>%s</p>%s<div class="copy"><input type="text" value="%s" readonly aria-label="Invite link">'
            '<button type="button" class="quiet small" data-copy>%sCopy</button></div><p class="note">%s</p>') % (
        lead, qr(link), esc(link), icon("copy"), after)
    return sheet("invite", title, body, '<button type="button" data-close>Done</button>', autoshow=True)


def invite_link(st, name):
    at = S.address(st["settings"])
    if st["settings"]["ACCESS"] == "accounts":
        return "kav://server?" + urllib.parse.urlencode({"address": at, "invite": S.people().invite(name)})
    return "kav://server?" + urllib.parse.urlencode({"address": at})


def qr(text):
    """A code for Kav to scan, drawn in the browser (static/qrcode.js, Kazuhiko Arase's qrcode-generator)."""
    return '<div class="qr" data-qr="%s" role="img" aria-label="QR code"></div>' % esc(text)


# The parts, as cards: the wizard's steps and the Parts page show the same ones.

def live_card(s):
    mot = ('<p class="note">Its key is approved for one IP address: this machine\'s. Apply on <a href="https://www.gov.il/'
           'he/departments/general/real_time_information_siri" rel="noreferrer">its page</a>.</p>')
    other = field("LIVE_URL", "Its address", s["LIVE_URL"], placeholder="http://my-source:8100/siri",
                  hint="Anything that answers SIRI StopMonitoring as the ministry does (server/LIVE.md).")
    # One key and one test for both sources (it's MOT_KEY either way); out of sight when there's no source.
    keyed = fold(('%s<div class="actions"><button class="quiet small" data-test-live data-wait="Asking…">Test</button>'
                  '<span id="live-said" class="note"></span></div>') % field(
        "MOT_KEY", "Key", s["MOT_KEY"], "password", hint="The ministry's key, or your source's if it wants one (sent as Key=)."),
        "keyed")
    return (option("live", "mot", "The Ministry of Transport", s["live"],
                   "Live buses for all of Israel, with your own key.", mot) +
            option("live", "other", "Another source", s["live"],
                   "A live source of your own: a GTFS-Realtime feed through Kav's adapter, say.", other) +
            option("live", "none", "None", s["live"],
                   "No live buses here: phones take them from Kav's server or elsewhere.") + keyed)


def address_card(s):
    domain = field("DOMAIN", "Domain", s["DOMAIN"], placeholder="kav.example.com",
                   hint="It must point at this machine, with ports 80 and 443 open to it. The certificate comes by itself.")
    proxy = field("PUBLIC_URL", "The address your proxy or tunnel gives it", s["PUBLIC_URL"], placeholder="kav.example.com",
                  hint="Send all of https://&lt;address&gt;/ to port 8080 on this machine. Cloudflare Tunnel is a free way "
                       "that needs no open ports.")
    return (option("address", "domain", "A domain for this machine", s["address"], more=domain) +
            option("address", "proxy", "My own proxy or tunnel", s["address"], more=proxy))


def access_card(s):
    return (option("ACCESS", "accounts", "People with an account", s["ACCESS"],
                   "Each person signs in once on each phone, or scans the invite you give them.") +
            option("ACCESS", "open", "Anyone", s["ACCESS"],
                   "No signing in. Anyone who knows the address can use the server, up to 120 requests a minute each."))


def parts_card(s, mem):
    own = (field("OTP_URL", "Your OpenTripPlanner", s["OTP_URL"],
                 placeholder="http://my-otp:8080/otp/gtfs/v1") +
           field("PLAN_URL", "Or a planner you wrote (Kav's routes format)",
                 s["PLAN_URL"], placeholder="http://my-planner:9000/plan", hint="server/PLANNER.md"))
    where = next((k for k, v in S.AREAS.items() if v == s["area"]), "custom" if s["area"] else "all")
    names = (("all", "All of Israel"), ("gush-dan", "Gush Dan"),
             ("jerusalem", "Jerusalem"), ("haifa", "Haifa and the Krayot"),
             ("beersheba", "Beersheba"), ("custom", "Another area"))
    whole, one = S.needs(dict(s, planner="kav", area=""))[1], S.needs(dict(s, planner="kav", area="x"))[1]
    small = mem and mem < whole and not s["area"]
    warn = fold('<p class="warn">This machine has %s GB; all of Israel needs about %g GB at night, one area about %g GB.</p>'
                % (mem, whole, one), "whole") if small else ""
    area = (warn + '<label class="f" for="where">Where</label><select id="where" name="where">%s</select>%s'
            '<p class="note">One area: about 4 GB while it builds, 2 GB while it answers, and live buses there alone. The '
            'app\'s timetable stays all of Israel\'s.</p>') % (
        "".join('<option value="%s"%s>%s</option>' % (k, " selected" if k == where else "", esc(n)) for k, n in names),
        fold(field("area", "south,west,north,east", s["area"] if where == "custom" else "",
                   placeholder="32.00,34.75,32.15,34.90"), "custom"))
    planner = (option("planner", "kav", "Kav's planner", s["planner"],
                      "Built here every night: for all of Israel, about 16 GB while it builds, 5 GB while it answers.",
                      area + check("official", "And a second planner on the ministry's own times", s["official"],
                                   "Its routes come after Kav's own. As much memory again.")) +
               option("planner", "own", "My own planner", s["planner"], more=own) +
               option("planner", "off", "No planner", s["planner"],
                      "Phones plan trips elsewhere."))
    pack = s["pack"]
    pack_more = field("pack_url", "The model pack's address", pack.get("url", ""),
                      placeholder="https://github.com/me/kav/releases/download/v1/model.tar.gz",
                      hint=("Now: %s %s, for %s." % (pack.get("name"), pack.get("version"), pack.get("inputs"))
                            if pack.get("url") else "A model of your own, packed with its manifest (server/PREDICTOR.md)."))
    url_more = field("PREDICT_URL", "Its address", s["PREDICT_URL"],
                     placeholder="http://my-predictor:8000/now.json", hint="server/selfhost/README.md")
    predictor = (option("predictor", "kav", "Kav's arrival times", s["predictor"],
                        "Kav's own model, about 2.5 GB.") +
                 option("predictor", "pack", "Kav's predictor with my own model",
                        s["predictor"], more=pack_more) +
                 option("predictor", "url", "My own predictor, at an address",
                        s["predictor"], more=url_more) +
                 option("predictor", "file", "My own predictor, on this machine",
                        s["predictor"], "It writes data/eta/out/now.json.") +
                 option("predictor", "off", "The ministry's times", s["predictor"],
                        "No predictor: the times the ministry's live source gives."))
    stages = "".join(field("stage_" + n, n, s["stages"].get(n, ""), placeholder="Kav's own")
                     for n in S.STAGES)
    data = ('<details><summary>Timetable, map and build steps</summary>%s%s<p class="note">A build step replaced by a '
            'command of your own, run in the build container with your scripts in ./stages. Empty: Kav\'s own. skip: left '
            'out. %s</p>%s</details>') % (
        field("GTFS_URL", "Timetable (GTFS zip)", s["GTFS_URL"],
              placeholder="https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip"),
        field("OSM_URL", "Map (OpenStreetMap .osm.pbf)", s["OSM_URL"],
              placeholder="https://download.geofabrik.de/asia/israel-and-palestine-latest.osm.pbf"), learn("build-steps"), stages)
    return (card(planner, "Trip planner", "the-trip-planner") + card(predictor, "Arrival times", "arrival-times") +
            card(alerts_inner(s), "Line alerts", "line-alerts") + card(data))


def alerts_inner(s):
    feed = (field("ALERTS_URL", "The feed's address", s["ALERTS_URL"], placeholder="https://example.com/service-alerts.pb",
                  hint="The Ministry of Transport sends its own on request to ptsupport@mot.gov.il. Asked every five "
                       "minutes, never more than 12 times an hour.") +
            field("ALERTS_HEADERS", "Headers it wants, as JSON", s["ALERTS_HEADERS"],
                  placeholder='{"Authorization": "Bearer ..."}'))
    return (option("alerts", "gtfsrt", "A GTFS-Realtime alerts feed", s["alerts"], more=feed) +
            option("alerts", "off", "None", s["alerts"], "Phones take their alerts elsewhere.") +
            fold(check("english", "And in English", s["english"],
                       "Translated here, each text once, about 0.5 GB. Line numbers, stop codes and times stay as "
                       "written."), "english"))


def memory_inner(s, mem):
    running, peak = S.needs(s)
    share = min(100, round(peak / mem * 100)) if mem else 0
    return '<p>%s</p><div class="meter"><i class="%s" style="width:%d%%"></i></div><p class="note">%s</p>' % (
        "About %g GB while running, %g GB in all at night, while it builds." % (running, peak),
        "over" if mem and peak > mem else "", share,
        "This machine has %s GB." % mem)


def memory_card(s, mem):
    # Redrawn as the parts are chosen (JS, /fragment/memory), before anything is saved.
    return card('<div id="memory">%s</div>' % memory_inner(s, mem), "Memory", "memory")


def area_of(text):
    """A custom area as typed, tidied, or ValueError: four numbers, south of north, west of east, in Israel."""
    try:
        south, west, north, east = (float(x) for x in text.replace(" ", "").split(","))
    except ValueError:
        raise ValueError("An area is four numbers: south,west,north,east.")
    s0, w0, n0, e0 = S.ISRAEL
    if not (s0 <= south < north <= n0 and w0 <= west < east <= e0):
        raise ValueError("That area isn't in Israel, or its sides are the wrong way round.")
    return "%g,%g,%g,%g" % (south, west, north, east)


def read_parts(s, form):
    """The cards' fields into the settings; a model pack's address is fetched and checked as it's given."""
    for k in ("live", "address", "ACCESS", "planner", "predictor", "alerts"):
        if k in form:
            s[k] = form[k]
    for k in ("MOT_KEY", "LIVE_URL", "OTP_URL", "PLAN_URL", "PREDICT_URL", "GTFS_URL", "OSM_URL", "ALERTS_URL", "ALERTS_HEADERS"):
        if k in form:
            s[k] = form[k].strip()
    if "where" in form:
        s["area"] = S.AREAS.get(form["where"]) or (area_of(form.get("area", "")) if form["where"] == "custom" else "")
    if s["planner"] != "kav":
        s["area"] = ""  # the area is Kav's planner's, chosen under it
    for k in ("DOMAIN", "PUBLIC_URL"):
        if k in form:
            s[k] = form[k].strip().removeprefix("https://").strip("/")
    if "planner" in form:
        s["official"] = form.get("official") == "on"
    if "alerts" in form:
        s["english"] = form.get("english") == "on"
    if any(("stage_" + n) in form for n in S.STAGES):
        s["stages"] = {n: form.get("stage_" + n, "").strip() for n in S.STAGES}
    url = form.get("pack_url", "").strip()
    if s["predictor"] == "pack" and url and url != s["pack"].get("url"):
        s["pack"] = S.check_pack(url)
    if s["predictor"] == "pack" and not s["pack"].get("url"):
        raise ValueError("The model pack's address is missing.")
    if "live" in form and s["live"] == "other" and not s["LIVE_URL"]:
        raise ValueError("The live source's address is missing.")
    if "planner" in form and s["planner"] == "own" and not (s["OTP_URL"] or s["PLAN_URL"]):
        raise ValueError("Your planner's address is missing.")
    if "predictor" in form and s["predictor"] == "url" and not s["PREDICT_URL"]:
        raise ValueError("Your predictor's address is missing.")
    if "alerts" in form and s["alerts"] == "gtfsrt":
        if not s["ALERTS_URL"]:
            raise ValueError("The alerts feed's address is missing.")
        try:
            headers = json.loads(s["ALERTS_HEADERS"] or "{}")
        except ValueError:
            headers = None
        if not isinstance(headers, dict) or not all(isinstance(v, str) for v in headers.values()):
            raise ValueError('The feed\'s headers are a JSON object of text, like {"Authorization": "Bearer ..."}.')


def wizard(st, step, csrf, who, said=""):
    s, m = st["settings"], S.machine()
    n = STEPS.index(step)
    lead = ""
    # What this machine has comes up only where it matters: a problem on the first step, the facts beside the parts.
    problems = ([("bad", "Docker isn't answering on this machine, so nothing can start. Install Docker, then reload this "
                         "page.")] if not m["docker"] else []) + \
        ([("warn", "Only %s GB of disk is free here; Kav needs about 10 GB." % m["disk"])] if m["disk"] < 10 else []) + \
        ([("warn", "This machine has one processor; the nightly build will be slow.")] if m["cpus"] < 2 else [])
    if step == "live":
        body = "".join(alert(k, t) for k, t in problems) + card(live_card(s))
        lead = "Where the buses' live positions come from."
    elif step == "address":
        body = card(address_card(s))
        lead = "How phones reach this server. Kav talks to servers over HTTPS only."
    elif step == "access":
        body, lead = card(access_card(s)), "Who may connect a phone to this server."
    elif step == "parts":
        body = card('<p class="note">%s GB memory · %d processors · %s GB free disk · Docker %s</p>' % (
            m["memory"], m["cpus"], m["disk"], esc(m["docker"] or "not found")), "This machine", "what-you-need") + \
            parts_card(s, m["memory"]) + memory_card(s, m["memory"])
        lead = "What runs here. Any of it can be changed later."
    else:
        live = {"mot": "The ministry", "other": s["LIVE_URL"], "none": "None"}[s["live"]]
        planner = {"kav": "Kav" + (", " + area_name(s["area"]) if s["area"] else ", all of Israel"),
                   "own": s["PLAN_URL"] or s["OTP_URL"], "off": "None"}[s["planner"]]
        times = {"kav": "Kav's", "pack": s["pack"].get("name", ""), "url": s["PREDICT_URL"], "file": "My own, here",
                 "off": "The ministry's"}[s["predictor"]]
        alerts = {"gtfsrt": s["ALERTS_URL"], "off": "None"}[s["alerts"]]
        if s["alerts"] != "off" and s["english"]:
            alerts += ", in English too"
        rows = (("Live buses", live), ("Address", S.address(s) or "Not set"),
                ("Who may use it", "People with an account" if s["ACCESS"] == "accounts" else "Anyone"),
                ("Trip planner", planner), ("Arrival times", times), ("Line alerts", alerts))
        body = card("".join('<div class="row"><span class="grow note">%s</span><b>%s</b></div>' % (esc(a), esc(b))
                            for a, b in rows)) + memory_card(s, m["memory"])
        lead = "Everything else comes at once."
        if s["planner"] == "kav":
            lead += " Kav's planner builds for about %s first." % ("15 minutes" if s["area"] else "half an hour")
    title = STEP_NAMES[n]
    buttons = '<div class="actions nav">%s<button name="go" value="next">%s</button></div>' % (
        '<button class="quiet" name="go" value="back">Back</button>' if n else "", "Start" if step == "review" else "Next")
    guide = {"live": "live-buses", "address": "address-and-https", "access": "who-may-use-it",
             "parts": "the-parts-and-swapping-each-one", "review": "the-first-setup-step-by-step"}[step]
    return solo(title, '<h1>%s</h1><p class="lead">%s %s</p>%s<form method=post action=/wizard>%s%s%s</form>' % (
        esc(title), esc(lead), learn(guide), alert("bad", esc(said)) if said else "", hidden(csrf, step=step), body, buttons),
        who=who, csrf=csrf, progress=n)


def status_fragment(st, csrf):
    rows = S.services()
    out = "".join('<div class="row"><span class="dot %s"></span><span class="grow">%s</span><span class="note">%s</span></div>' % (
        "good" if state == "running" else "bad", esc(name), esc(status)) for name, state, status in rows) or \
        '<p class="note">Nothing running yet.</p>'
    start = st.get("start", {})
    if start.get("running"):
        out += '<p class="warn">Starting… this page updates by itself.</p>'
    elif start and not start.get("ok", True):
        out += ('<p class="bad">Starting failed:</p><pre>%s</pre><form method=post action=/start>%s'
                '<button class="small">Try again</button></form>') % (esc(start.get("log", "").strip()), hidden(csrf))
    return out


def stats_fragment():
    """The server's own numbers, along the top of the overview: what kav-live says of itself."""
    live = S.server_status()
    if not live:
        return ""
    caps = live.get("capabilities", {})
    try:
        day = time.strptime(live.get("timetable", ""), "%Y-%m-%d")
        timetable = "%d %s" % (day.tm_mday, time.strftime("%b", day))
    except ValueError:
        timetable = "None yet"
    tiles = (("Live vehicles", "{:,}".format(live.get("vehicles", 0)) if caps.get("live") else "Off"),
             ("Trip planner", ("Answering" if live["planner"] else "Not yet") if "planner" in live else "Off"),
             ("Arrival times", ("Kav's" if live.get("predictions") else "Not ready") if caps.get("predictions")
              else "The ministry's"),
             ("Timetable", timetable))
    return "".join('<div class="stat"><span class="note">%s</span><b>%s</b></div>' % (esc(a), esc(b)) for a, b in tiles)


def overview(st, csrf, who, said="", invite=None):
    s = st["settings"]
    build = S.last_build()
    last = build.get("last")
    if not last:
        built = '<div class="row"><span class="dot"></span><span class="grow">No nightly build yet.</span></div>'
    elif last.get("ok"):
        built = '<div class="row"><span class="dot good"></span><span class="grow">Last build finished</span>' \
                '<span class="note">%d min</span></div>' % last.get("minutes", 0)
    else:
        built = '<div class="row"><span class="dot bad"></span><span class="grow">Last build failed: %s</span>' \
                '<span class="note">%d min</span></div>' % (esc(last.get("said", "")), last.get("minutes", 0))
    if build.get("next"):
        built += '<div class="row"><span class="grow note">Next</span><span class="note">%s</span></div>' % esc(
            build["next"][:16].replace("T", " "))
    reach = S.reachable(s)
    there = {None: "", True: '<p class="good">Phones reach it at %s.</p>' % esc(S.address(s)),
             False: '<p class="bad">Not reachable at https://%s yet: check the domain, the ports or the tunnel.</p>'
                    % esc(S.address(s))}[reach]
    rebuild = '<form method=post action=/rebuild>%s<button class="quiet">%sRebuild now</button></form>' % (
        hidden(csrf), icon("build"))
    body = (page_head("Your Kav server", rebuild) + edited_alert(st) + (alert("good", esc(said)) if said else "") +
            '<div id="stats" class="stats">%s</div><div class="grid">' % stats_fragment() +
            card('<div id="status">%s</div>%s' % (status_fragment(st, csrf), there), "Running", "running-it-day-to-day") +
            card(built, "Nightly build", "the-nightly-build") +
            card('<p>In Kav: %s.</p><form method=post action=/people>%s<div class="actions"><button class="small">'
                 '%sShow my phone\'s code</button></div></form>' % (
                     scan_path(), hidden(csrf, action="invite", name=who, back="/"), icon("qr")), "Connect a phone", "connecting-a-phone") +
            card('<p class="note">This page can start and stop everything on this machine.%s</p><form method=post '
                 'action=/lock>%s<div class="actions"><button class="danger small">%sLock setup</button></div></form>' % (
                     " Locked, it doesn't update by itself either." if st["auto_update"] else "", hidden(csrf),
                     icon("lock")), "Setup page", "locking-the-setup-page") + '</div>' + (invite or ""))
    return layout("Overview", body, "/", who, csrf)


def parts_page(st, csrf, who, said="", ok=""):
    s, m = st["settings"], S.machine()
    body = (page_head("Parts", learn("the-parts-and-swapping-each-one")) + edited_alert(st) +
            (alert("bad", esc(said)) if said else "") + (alert("good", esc(ok)) if ok else "") +
            '<form method=post action=/parts>%s' % hidden(csrf) +
            card(live_card(s), "Live buses", "live-buses") + card(address_card(s), "Address", "address-and-https") +
            card(access_card(s), "Who may use it", "who-may-use-it") + parts_card(s, m["memory"]) + memory_card(s, m["memory"]) +
            '<div class="actions"><button>Save and apply</button></div></form>')
    return layout("Parts", body, "/parts", who, csrf)


def people_page(st, csrf, who, said="", code=None, ok=""):
    store = S.people()
    open_access = st["settings"]["ACCESS"] != "accounts"
    rows = []
    order = lambda p: (p["disabled"], not p["admin"], p["name"])
    for p in sorted(store.people(), key=order):
        name, devices = p["name"], p["devices"]
        role = ("Turned off", "off") if p["disabled"] else ("Admin", "admin") if p["admin"] else ("Member", "")
        phones = "%d phone%s" % (len(devices), "" if len(devices) == 1 else "s") if devices else "No phones"
        seen = time.strftime("%d.%m.%Y %H:%M", time.localtime(max(d["seen"] for d in devices))) if devices else "Never"

        just_row = ('hx-post="/people" hx-target="closest .prow" hx-select=\'.prow[data-name="%s"]\' '
                    'hx-swap="outerHTML transition:false"') % esc(name.lower())

        def act(action, label, cls="item", **more):
            return '<form method=post action=/people %s>%s<button class="%s" role="menuitem">%s</button></form>' % (
                just_row, hidden(csrf, action=action, name=name, **more), cls, label)
        menu = '<button type="button" class="item" role="menuitem" data-dialog="password" data-name="%s">Set a password…' \
               '</button>' % esc(name)
        menu += "".join(act("phone_out", "Sign out %s" % esc(d["model"] or "a phone"), device=d["id"]) for d in devices)
        if name != who:
            menu += "<hr>" + act("admin", "Remove admin" if p["admin"] else "Make admin") + \
                act("disable", "Turn on" if p["disabled"] else "Turn off") + \
                '<hr><button type="button" class="item danger" role="menuitem" data-dialog="remove" data-name="%s">' \
                'Remove…</button>' % esc(name)
        actions = ('<form method=post action=/people>%s<button class="icon-button" title="%s" aria-label="%s">%s</button></form>' % (
                hidden(csrf, action="invite", name=name), "Show the code to connect a phone" if open_access else
                "Invite code", "Invite code for %s" % esc(name), icon("qr"))) + \
            ('<div class="menu-wrap"><button type="button" class="icon-button" data-menu aria-haspopup="menu" '
             'aria-expanded="false" title="More" aria-label="More for %s">%s</button><div class="menu" role="menu">%s</div>'
             '</div>' % (esc(name), icon("more"), menu))
        rows.append(('<div class="prow person" data-name="%s"><div class="who"><span class="avatar">%s</span><div class="name">'
                     '<b>%s</b>%s<span class="sub">%s · %s</span></div></div><div><span class="role %s">%s</span></div>'
                     '<div class="cell-note">%s</div><div class="cell-note">%s</div><div>%s</div></div>') % (
            esc(name.lower()), esc(name[:1]), esc(name), ' <span class="note">(you)</span>' if name == who else "",
            role[0], phones, role[1], role[0], phones, seen, actions))
    tools = ('<div class="people-tools"><label class="search">%s<input type="search" placeholder="Search %d %s" '
             'aria-label="Search people" data-search></label><button type="button" class="small add" data-dialog="add">'
             '%sAdd someone</button></div>') % (icon("search"), len(rows), "person" if len(rows) == 1 else "people",
                                                icon("plus"))
    table = ('<div class="ptable" role="table"><div class="prow headings" role="row"><div>Name</div><div>Role</div>'
             '<div>Phones</div><div>Last seen</div><div></div></div>%s<p class="nobody">No one by that name.</p></div>') % (
        "".join(rows))
    cancel = '<button type="button" class="quiet" data-close>Cancel</button>'
    sheets = (
        sheet("add", "Add someone", field("name", "Username", fid="new-name") + field(
            "password", "Password", kind="password", fid="new-password",
            hint="Leave it empty and they sign in with an invite code instead."),
            cancel + '<button>Add</button>', "/people", hidden(csrf, action="add")) +
        sheet("password", 'Set a password for <span data-fill="name"></span>', field(
            "password", "New password", kind="password", fid="set-password", hint="Their phones stay signed in."),
            cancel + '<button>Set password</button>', "/people",
            hidden(csrf, action="password") + '<input type="hidden" name="name" data-fill="name">') +
        sheet("remove", 'Remove <span data-fill="name"></span>?', '<p>Their phones are signed out, and they can\'t use '
              'this server any more. This can\'t be undone.</p>', cancel + '<button class="danger-fill">Remove</button>',
              "/people", hidden(csrf, action="remove") + '<input type="hidden" name="name" data-fill="name">'))
    if code:
        sheets += invite_sheet(code[0], code[1], open_access)
    note = alert("warn", "The server is open to anyone, so phones don't need these accounts; they're kept for if you "
                         "switch back. Admins still sign in to this page with theirs.") if open_access else ""
    body = (page_head("People", learn("people")) + note + (alert("bad", esc(said)) if said else "") + (alert("good", esc(ok)) if ok else "") +
            '<section class="card people">%s%s</section>%s' % (tools, table, sheets))
    return layout("People", body, "/people", who, csrf)


def updates_page(st, csrf, who):
    now, new = S.version(), S.latest()
    u = st.get("update", {})
    inner = '<div class="row"><span class="grow note">Running</span><b>%s</b></div>' % (
        esc(now) if now else "A copy without a version")
    if u.get("running"):
        inner += '<p class="warn">Updating to %s…</p>' % esc(u.get("to"))
    elif new and S.newer(new["tag"], now):
        inner += ('<div class="row"><span class="grow note">Available</span><b>%s</b></div><details><summary>What\'s new'
                  '</summary><pre>%s</pre>%s</details><form method=post action=/updates>%s<div class="actions">'
                  '<button>Update to %s</button></div></form>') % (
            esc(new["name"]), esc(new["notes"]),
            '<p><a href="%s" rel="noreferrer">On GitHub</a></p>' % esc(new["url"]) if new.get("url") else "",
            hidden(csrf, action="update", tag=new["tag"]), esc(new["tag"]))
    elif new and not now:
        inner += ('<p class="note">Updates come by button only to a copy installed from a release (install.sh), not to '
                  'one built from the repository.</p>')
    elif new:
        inner += '<p class="good">This is the latest.</p>'
    else:
        inner += '<p class="note">Couldn\'t ask GitHub for the latest.</p>'
    if u.get("at") and not u.get("running"):
        if u.get("ok"):
            said = "Last update to %s: done." % u.get("to")
        elif u.get("got") is False:
            said = "Couldn't download %s. Nothing changed." % u.get("to")
        else:
            said = "%s didn't come up healthy, so it went back to %s." % (u.get("to"), u.get("from"))
        inner += '<p class="%s">%s</p>' % ("good" if u.get("ok") else "bad", esc(said))
        if not u.get("ok") and u.get("log"):
            inner += '<details><summary>What happened</summary><pre>%s</pre></details>' % esc(u["log"])
    auto = '<form method=post action=/updates>%s%s<div class="actions"><button class="quiet small">Save</button></div></form>' % (
        hidden(csrf, action="auto"), check("auto", "Update automatically", st["auto_update"],
                                           "Once a day, when a new release is out. If it doesn't come up healthy, it goes back."))
    body = page_head("Updates", learn("updates")) + '<div class="grid">%s%s</div>' % (
        card(inner, "Version"), card(auto, "Automatic updates"))
    return layout("Updates", body, "/updates", who, csrf)


def storage_page(st, csrf, who):
    sizes = S.sizes()
    names = {"history": "Recorded passings, accounts, trip links", "history-backfill": "Older passings, added by hand",
             "otp": "Planner: timetable, map, built graphs", "realtime": "Live updates for the planner",
             "eta": "Arrival times", "models": "Models", "signing": "The timetable's signing key",
             "caddy": "HTTPS certificates"}

    def size(mb):
        return "%.1f GB" % (mb / 1000) if mb >= 1000 else "%d MB" % mb
    rows = "".join('<div class="row"><span class="grow">%s <span class="note">%s/</span></span><b>%s</b></div>' % (
        esc(names.get(k, "")), esc(k), size(v)) for k, v in sizes.items())
    body = (page_head("Storage", learn("storage-and-backups")) + '<div class="grid">' +
            card(rows or '<p class="note">Nothing here yet.</p>', "data/") +
            '<div>' +
            card('<p>When each bus passed each stop, the last ten weeks: for training a model of your own.</p>'
                 '<div class="actions"><a class="button quiet small" href="/export/history.tar" hx-boost="false">Download the recorded '
                 'passings</a></div>', "History") +
            card('<p class="note">Everything this server keeps is in data/ beside it. Back that folder up, and nothing '
                 'else matters.</p>', "Backups") + '</div></div>')
    return layout("Storage", body, "/storage", who, csrf)


def owner_page(code, said=""):
    return solo("Welcome", (
        '<h1>Welcome</h1><p class="lead">First, your own account: you run this server, and only admins may open this '
        'page.</p>%s<form method=post action=/owner><input type="hidden" name="code" value="%s">%s%s%s<div class="actions nav">'
        '<button>Create my account</button></div></form>') % (
        alert("bad", esc(said)) if said else "", esc(code), field("name", "Username"),
        field("password", "Password", kind="password", hint="At least 8 characters."),
        field("again", "Password again", kind="password")))


def signin_page(said="", back="/"):
    return solo("Sign in", (
        '<h1>Sign in</h1><p class="lead">With a server admin\'s account. %s</p>%s<form method=post action=/signin>'
        '<input type="hidden" name="back" value="%s">%s%s<div class="actions nav"><button>Sign in</button></div></form>') % (
        learn("a-lost-admin-password", "Forgot it?"), alert("bad", esc(said)) if said else "", esc(back), field("name", "Username"),
        field("password", "Password", kind="password")))


def message_page(title, text, more=""):
    """A page with one thing to say, in the middle."""
    return solo(title, '<h1>%s</h1><p class="lead">%s</p>%s' % (esc(title), text, more))


def locked_page():
    return message_page("Setup is locked", "The server keeps running. To open this page again, on the machine:",
                        '<pre>cd %s &amp;&amp; docker compose start setup</pre><p class="lead">%s</p>' % (
                            esc(S.DIR), learn("locking-the-setup-page")))


TAILSCALE = ipaddress.ip_network("100.64.0.0/10")


def nearby(address):
    """Whether a request comes from this machine or its own network (or a Tailscale one): the only places this page,
    which can start anything on the machine, answers. From further away, an SSH tunnel makes it local."""
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return False
    ip = getattr(ip, "ipv4_mapped", None) or ip
    return ip.is_loopback or ip.is_private or ip.is_link_local or ip in TAILSCALE


def far_page():
    return message_page("Only from nearby", "The setup page answers on this machine's own network only. From elsewhere, "
                        "tunnel to it:", "<pre>ssh -L 8090:localhost:8090 &lt;the machine&gt;</pre><p class=\"lead\">Then "
                        "open http://localhost:8090. %s</p>" % learn("opening-the-setup-page"))


class Handler(http.server.BaseHTTPRequestHandler):
    def parse_request(self):
        # Every request, before its method: refused unless it comes from nearby.
        if not super().parse_request():
            return False
        if not nearby(self.client_address[0]):
            self.reply(403, far_page())
            return False
        return True

    def reply(self, status, body, kind="text/html; charset=utf-8", headers=(), cache="no-store"):
        raw = body if isinstance(body, bytes) else body.encode()
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", cache)
        self.send_header("Referrer-Policy", "same-origin")  # so this page's own posts carry its Origin
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
                                                    "img-src 'self' data:; frame-ancestors 'none'")
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def go(self, where, headers=()):
        self.send_response(303)
        self.send_header("Location", where)
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def form(self):
        length = min(int(self.headers.get("Content-Length") or 0), 65536)
        raw = self.rfile.read(length).decode()
        if (self.headers.get("Content-Type") or "").startswith("application/json"):
            try:
                return json.loads(raw)
            except ValueError:
                return {}
        return {k: v[0] for k, v in urllib.parse.parse_qs(raw, keep_blank_values=True).items()}

    def who(self):
        """The admin signed in to this page, while they still are one: turned off, made a plain user or removed on the
        People page, their session ends with it."""
        sid, sess = S.session_of(self.headers.get("Cookie"))
        if sess and not any(p["name"] == sess["name"] and p["admin"] and not p["disabled"] for p in S.people().people()):
            S.sessions.pop(sid, None)
            return None, None
        return sid, sess

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        name = url.path.removeprefix("/static/")
        if url.path.startswith("/static/") and name in STATIC:
            with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "static", name), "rb") as f:
                return self.reply(200, f.read(), "text/javascript; charset=utf-8", cache="public, max-age=86400")
        q = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
        store = S.people()
        if not store.has_admin():
            if q.get("code") and q["code"] == S.one_time_code():
                return self.reply(200, owner_page(q["code"]))
            return self.reply(403, message_page("Kav server", "Open the address install.sh showed, with its code."))
        sid, sess = self.who()
        if not sess:
            if url.path.startswith("/fragment/"):
                return self.reply(401, "")
            return self.reply(200, signin_page(back=self.path))
        st, who, csrf = S.load(), sess["name"], sess["csrf"]
        if url.path == "/fragment/stats":
            return self.reply(200, stats_fragment())
        if url.path == "/fragment/status":
            return self.reply(200, (status_fragment(st, csrf)))
        if url.path == "/fragment/memory":
            s = dict(st["settings"])
            s.update({k: q[k] for k in ("planner", "predictor") if q.get(k) in ("kav", "own", "off", "pack", "url", "file")})
            s["official"] = q.get("official") == "on"
            if q.get("alerts") in ("gtfsrt", "off"):
                s["alerts"], s["english"] = q["alerts"], q.get("english") == "on"
            if q.get("where") in S.AREAS or q.get("where") == "all":
                s["area"] = S.AREAS.get(q["where"], "")
            elif q.get("where") == "custom":
                s["area"] = s["area"] or "custom"
            return self.reply(200, (memory_inner(s, S.machine()["memory"])))
        if url.path == "/export/history.tar":
            # Streamed: no length up front, the connection's end is the file's (HTTP/1.0).
            self.send_response(200)
            self.send_header("Content-Type", "application/x-tar")
            self.send_header("Content-Disposition", 'attachment; filename="kav-history.tar"')
            self.end_headers()
            try:
                S.history_export(self.wfile)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        if url.path == "/wizard" or not st["done"]:
            # Back where the owner left it, without a step asked for.
            step = q.get("step") if q.get("step") in STEPS else st.get("at") if st.get("at") in STEPS else STEPS[0]
            return self.reply(200, wizard(st, step, csrf, who))
        if url.path == "/" and st.pop("welcome", False):
            S.save(st)
            return self.reply(200, overview(st, csrf, who, invite=invite_sheet(
                who, invite_link(st, who), st["settings"]["ACCESS"] != "accounts", title="Your server is starting",
                lead="It's ready in a few minutes. Meanwhile, connect your phone: in Kav, open %s." % scan_path())))
        page = {"/": overview, "/parts": parts_page, "/people": people_page, "/updates": updates_page,
                "/storage": storage_page}.get(url.path)
        if page is None:
            return self.reply(404, message_page("Not found", "There's no such page here."))
        return self.reply(200, page(st, csrf, who))

    def do_POST(self):
        path = urllib.parse.urlsplit(self.path).path
        form = self.form()
        # Only from this page itself: a request another site's page makes in the owner's browser is turned away.
        origin = self.headers.get("Origin")
        if origin and urllib.parse.urlsplit(origin).netloc != self.headers.get("Host"):
            return self.reply(403, message_page("Kav server", "Only this page itself can change the server."))
        store = S.people()
        if path == "/owner":
            if store.has_admin() or form.get("code") != S.one_time_code():
                return self.go("/")
            if form.get("password") != form.get("again"):
                return self.reply(200, owner_page(form.get("code"), "The passwords aren't the same."))
            try:
                name = store.add(form.get("name", ""), form.get("password", ""), admin=True)
            except S.accounts.Refused as e:
                return self.reply(200, owner_page(form.get("code"), str(e)))
            os.remove(S.CODE_FILE)
            return self.go("/wizard", [("Set-Cookie", self.cookie(S.new_session(name)))])
        if path == "/signin":
            try:
                person = store.verify(form.get("name", ""), form.get("password", ""), setup=True)
                if not person["admin"]:
                    raise S.accounts.Refused("Only the server's admins may open this page.")
            except S.accounts.Refused as e:
                return self.reply(200, signin_page(str(e), form.get("back", "/")))
            back = form.get("back", "/")
            back = back if back.startswith("/") and not back.startswith("//") else "/"  # this page's own, never elsewhere
            return self.go(back, [("Set-Cookie", self.cookie(S.new_session(person["name"])))])
        sid, sess = self.who()
        token = self.headers.get("X-CSRF") if path.startswith("/api/") else form.get("csrf")
        if not sess or token != sess["csrf"]:
            return self.go("/")
        st, who, csrf = S.load(), sess["name"], sess["csrf"]
        if path == "/signout":
            S.sessions.pop(sid, None)
            return self.go("/", [("Set-Cookie", "kav_setup=; Path=/; Max-Age=0")])
        if path == "/api/test-live":
            s = dict(st["settings"])
            s.update({k: str(form.get(k) or "") for k in ("live", "MOT_KEY", "LIVE_URL") if form.get(k) is not None})
            ok, said = S.test_live(s)
            return self.reply(200, json.dumps({"ok": ok, "said": ("Works: " if ok else "Didn't work: ") + said}),
                              "application/json")
        if path == "/wizard":
            step = form.get("step") if form.get("step") in STEPS else STEPS[0]
            n = STEPS.index(step)
            try:
                read_parts(st["settings"], form)
            except (ValueError, OSError) as e:
                return self.reply(200, wizard(st, step, csrf, who, str(e)))
            if step == "address" and form.get("go") == "next" and not S.address(st["settings"]):
                return self.reply(200, wizard(st, step, csrf, who, "Phones need an address to reach it."))
            st["at"] = STEPS[max(0, n - 1)] if form.get("go") == "back" else STEPS[min(n + 1, len(STEPS) - 1)]
            S.save(st)
            if form.get("go") == "back" or step != "review":
                return self.go("/wizard?step=" + st["at"])
            st["done"] = True
            st["welcome"] = True
            S.start(st)
            return self.go("/")
        if path == "/parts":
            try:
                read_parts(st["settings"], form)
            except (ValueError, OSError) as e:
                return self.reply(200, parts_page(st, csrf, who, str(e)))
            S.save(st)
            S.start(st)
            return self.reply(200, parts_page(S.load(), csrf, who, ok="Saved. Starting what changed…"))
        if path == "/start":
            S.start(st)
            return self.go("/")
        if path == "/rebuild":
            S.rebuild_now()
            return self.reply(200, overview(st, csrf, who, "The build starts within a minute."),
                              headers=[("HX-Replace-Url", "/")])
        if path == "/people":
            action, name = form.get("action"), form.get("name", "")
            try:
                if action == "add":
                    name = store.add(name, form.get("password") or None)
                    action = "invite" if not form.get("password") else None
                elif action == "admin":
                    store.update(name, admin=not next(p for p in store.people() if p["name"] == name)["admin"])
                elif action == "disable":
                    store.update(name, disabled=not next(p for p in store.people() if p["name"] == name)["disabled"])
                elif action == "remove" and name != who:
                    store.remove(name)
                elif action == "phone_out":
                    store.sign_out(form.get("device", ""))
                elif action == "password":
                    store.set_password(name, form.get("password", ""))
                    return self.reply(200, people_page(st, csrf, who, ok="%s's password is set. Their phones stay signed in." % name))
            except (S.accounts.Refused, StopIteration) as e:
                return self.reply(200, people_page(st, csrf, who, str(e)))
            code = (name, invite_link(st, name)) if action == "invite" else None
            if code and form.get("back") == "/":
                return self.reply(200, overview(st, csrf, who, invite=invite_sheet(
                    name, code[1], st["settings"]["ACCESS"] != "accounts", title="Connect your phone")),
                    headers=[("HX-Replace-Url", "/")])  # still the overview's address, as htmx shows it
            return self.reply(200, people_page(st, csrf, who, code=code))
        if path == "/updates":
            if form.get("action") == "auto":
                st["auto_update"] = form.get("auto") == "on"
                S.save(st)
            elif form.get("action") == "update" and form.get("tag") and S.newer(form["tag"], S.version()):
                threading.Thread(target=S.update, args=(form["tag"],), daemon=True).start()
                time.sleep(0.2)
            return self.go("/updates")
        if path == "/lock":
            self.reply(200, locked_page())
            threading.Timer(1.0, S.stop_self).start()
            return
        return self.go("/")

    def cookie(self, sid):
        return "kav_setup=%s; Path=/; HttpOnly; SameSite=Strict; Max-Age=%d" % (sid, S.SESSION_S)

    def log_message(self, *args):
        pass
