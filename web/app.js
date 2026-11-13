'use strict';
/* nuam · Mantenedor de Calificaciones Tributarias — cliente (SPA sin dependencias). */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const S = { user: null, csrf: null, idle: 900, lastPing: 0, warned: false, rid: 0 };
const PAIS = { CL: 'Chile', PE: 'Perú', CO: 'Colombia' };
const NAV = {
  CORREDOR: [['dashboard', 'Dashboard'], ['mantenedor', 'Mantenedor'], ['carga', 'Carga masiva'], ['periodos', 'Periodos'], ['tokens', 'API / Tokens']],
  SUPERVISOR: [['dashboard', 'Dashboard'], ['mantenedor', 'Mantenedor'], ['aprobaciones', 'Aprobaciones'], ['periodos', 'Periodos'], ['tokens', 'API / Tokens']],
  ADMIN: [['dashboard', 'Dashboard'], ['mantenedor', 'Mantenedor'], ['carga', 'Carga masiva'], ['periodos', 'Periodos'], ['usuarios', 'Usuarios y roles'], ['auditoria', 'Auditoría'], ['respaldos', 'Respaldos'], ['tokens', 'API / Tokens']],
  AUDITOR: [['auditoria', 'Auditoría']]
};

function toast(t) { const d = document.createElement('div'); d.textContent = t; $('#toast').append(d); setTimeout(() => d.remove(), 4500); }
const msg = (cls, t) => `<div class="msg ${cls}">${esc(t)}</div>`;
const fmt = v => v == null ? '' : String(v).replace('T', ' ').slice(0, 19);
const fnum = v => v == null ? '' : Number(v).toLocaleString('es-CL', { maximumFractionDigits: 10 });

async function api(method, url, body, raw) {
  const h = {}, rid = S.rid;   // si el usuario cambia de pantalla mientras responde, se descarta (evita pisar la vista nueva)
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (method !== 'GET' && S.csrf) h['X-CSRF'] = S.csrf;
  const r = await fetch(url, { method, headers: h, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) });
  if (raw && r.ok) return r;
  let d = null; try { d = await r.json(); } catch (e) { /* sin cuerpo */ }
  if (rid !== S.rid) { const e = new Error('cancelada'); e.stale = true; throw e; }
  if (!r.ok) {
    if (r.status === 401 && S.user) { S.user = null; showLogin(d && d.expirada ? 'Su sesión se cerró por inactividad. Inicie sesión nuevamente.' : 'Su sesión expiró.'); }
    const e = new Error((d && d.error) || 'Error ' + r.status); e.status = r.status; e.data = d || {}; throw e;
  }
  return d;
}
const fail = (box, e) => { if (e.stale) return; if (box) box.innerHTML = msg('err', e.message); else toast(e.message); };

/* ------------------------------------------------------------------ acceso */
function showLogin(aviso) {
  clearInterval(S.timer); S.user = null; S.csrf = null;
  $('#app').innerHTML = `<div class="login card"><div class="brand">nuam<b>.</b><small>Mantenedor de Calificaciones Tributarias</small></div>
  ${aviso ? msg('warn', aviso) : ''}<div id="lb"></div>
  <form id="lf" class="row" style="flex-direction:column;align-items:stretch">
    <label>Correo<input name="correo" type="email" autocomplete="username" required autofocus></label>
    <label>Contraseña<input name="password" type="password" autocomplete="current-password" required></label>
    <button class="pri">Ingresar</button></form></div>`;
  $('#lf').onsubmit = async ev => {
    ev.preventDefault(); const f = new FormData(ev.target);
    try { const d = await api('POST', '/auth/login', { correo: f.get('correo'), password: f.get('password') }); d.siguiente === 'setup2fa' ? setup2fa(d) : codigo2fa(d); }
    catch (e) { fail($('#lb'), e); }
  };
}
function codigo2fa(d) {
  $('#app').innerHTML = `<div class="login card"><div class="brand">nuam<b>.</b><small>Verificación en dos pasos</small></div><div id="lb"></div>
  ${d.dispositivoNuevo ? msg('info', 'Dispositivo no reconocido: la verificación es obligatoria.') : ''}
  <p>Ingrese el código de 6 dígitos de su app de autenticación.</p>
  ${d.demoCodigo ? `<div class="demo">MODO DEMO — código vigente: <b>${esc(d.demoCodigo)}</b></div>` : ''}
  <form id="cf" class="row" style="flex-direction:column;align-items:stretch"><label>Código<input name="c" inputmode="numeric" maxlength="6" pattern="\\d{6}" required autofocus></label><button class="pri">Verificar</button></form></div>`;
  $('#cf').onsubmit = async ev => {
    ev.preventDefault();
    try { entrar(await api('POST', '/auth/2fa', { codigo: new FormData(ev.target).get('c') })); }
    catch (e) { if (e.status === 423 || e.status === 401 && !e.data.restantes) return showLogin(e.message); fail($('#lb'), e); }
  };
}
async function setup2fa() {
  let i; try { i = await api('GET', '/auth/2fa/setup'); } catch (e) { return showLogin(e.message); }
  $('#app').innerHTML = `<div class="login card"><div class="brand">nuam<b>.</b><small>Configurar verificación en dos pasos</small></div><div id="lb"></div>
  ${msg('info', 'Su cuenta debe activar la autenticación en dos pasos antes de continuar.')}
  <ol>${i.pasos.map(p => `<li>${esc(p)}</li>`).join('')}</ol>
  <div class="secret">${esc(i.secreto)}</div><p class="sub">URI: <small>${esc(i.uri)}</small></p>
  ${i.demoCodigo ? `<div class="demo">MODO DEMO — código vigente: <b>${esc(i.demoCodigo)}</b></div>` : ''}
  <form id="cf" class="row" style="flex-direction:column;align-items:stretch"><label>Código de 6 dígitos<input name="c" inputmode="numeric" maxlength="6" required autofocus></label><button class="pri">Activar y continuar</button></form></div>`;
  $('#cf').onsubmit = async ev => {
    ev.preventDefault();
    try { entrar(await api('POST', '/auth/2fa/setup', { codigo: new FormData(ev.target).get('c') })); }
    catch (e) { if (e.status === 423) return showLogin(e.message); fail($('#lb'), e); }
  };
}
function entrar(me) {
  S.user = me.usuario; S.csrf = me.csrf; S.idle = me.idleSegundos; S.warned = false;
  const first = NAV[S.user.rol][0][0];
  if (!location.hash || location.hash === '#') location.hash = '#' + first;
  shell(); route(); iniciarInactividad();
}

/* ------------------------------------------------------------------ marco */
function shell() {
  const u = S.user;
  $('#app').innerHTML = `<div class="shell"><aside><div class="brand">nuam<b>.</b><small>Calificaciones tributarias</small></div>
  <nav>${NAV[u.rol].map(([k, t]) => `<a href="#${k}" data-k="${k}">${esc(t)}</a>`).join('')}</nav>
  <div class="sideft"><b>${esc(u.nombre)}</b><br>${esc(u.rol)}${u.corredora ? ' · ' + esc(u.corredora) : ''}<button id="out">Cerrar sesión</button></div></aside>
  <main><div class="top"><div id="head"></div><div class="row" style="align-items:center"><span class="sub" id="idle"></span>
  ${u.rol === 'AUDITOR' ? '' : '<div class="bell" id="bell" title="Notificaciones">🔔<i id="nn" hidden>0</i></div>'}</div></div><div id="alert"></div><div id="view"></div></main></div>`;
  $('#out').onclick = async () => { try { await api('POST', '/auth/logout', {}); } catch (e) { /* ya cerrada */ } location.hash = ''; showLogin('Sesión cerrada.'); };
  if ($('#bell')) { $('#bell').onclick = abrirNotificaciones; cargarNotif(); }
}
async function cargarNotif() {
  try { const d = await api('GET', '/api/v1/notificaciones'); const n = $('#nn'); if (n) { n.textContent = d.noLeidas; n.hidden = !d.noLeidas; } return d; } catch (e) { return null; }
}
async function abrirNotificaciones() {
  const old = $('.notes'); if (old) return old.remove();
  const d = await cargarNotif(); if (!d) return;
  const box = document.createElement('div'); box.className = 'notes';
  box.innerHTML = d.items.length ? d.items.map(n => `<div class="${n.leida ? '' : 'un'}" data-id="${n.id}"><b>${esc(n.tipo)}</b> · <small>${fmt(n.fecha)}</small><br>${esc(n.mensaje)}</div>`).join('') : '<div class="empty">Sin notificaciones</div>';
  $('main').append(box);
  $$('div[data-id]', box).forEach(el => el.onclick = async () => { const r = await api('POST', `/api/v1/notificaciones/${el.dataset.id}/leer`, {}); box.remove(); if (r.url) location.hash = r.url; cargarNotif(); });
}
async function alertas() {
  if (!['CORREDOR', 'SUPERVISOR', 'ADMIN'].includes(S.user.rol)) return;
  try { const a = await api('GET', '/api/v1/alertas'); $('#alert').innerHTML = a.map(x => `<div class="msg err">⚠ El plazo de la DJ 1948 del periodo <b>${esc(x.periodo)}</b> venció hace ${x.dias_vencido} día(s) (${esc(x.fecha_limite)}). Quedan ${x.pendientes} calificación(es) pendiente(s). <a href="#mantenedor?periodo=${esc(x.periodo)}&pendientes=1">Ver pendientes</a></div>`).join(''); } catch (e) { /* sin alertas */ }
}

/* ------------------------------------------------------------------ inactividad (HU-20) */
function iniciarInactividad() {
  clearInterval(S.timer);
  const ping = () => { if (Date.now() - S.lastPing > 30000 && S.user) { S.lastPing = Date.now(); api('POST', '/auth/ping', {}).catch(() => { }); } };
  ['click', 'keydown'].forEach(ev => document.addEventListener(ev, ping, { passive: true }));
  S.timer = setInterval(async () => {
    if (!S.user) return;
    try {
      const r = await api('GET', '/auth/idle'); const rem = r.restante;
      const el = $('#idle'); if (el) el.textContent = rem <= 120 ? `Sesión expira en ${rem}s` : '';
      if (rem <= 60 && !$('#warn')) {
        const m = document.createElement('div'); m.id = 'warn'; m.className = 'modal';
        m.innerHTML = '<div class="box"><h2>¿Sigue ahí?</h2><p>Por seguridad, su sesión se cerrará en menos de un minuto por inactividad.</p><button class="pri" id="keep">Seguir conectado</button></div>';
        document.body.append(m); $('#keep').onclick = async () => { await api('POST', '/auth/ping', {}); m.remove(); };
      }
      if (rem > 60 && $('#warn')) $('#warn').remove();
    } catch (e) { const w = $('#warn'); if (w) w.remove(); }
  }, 5000);
}

/* ------------------------------------------------------------------ router */
window.addEventListener('hashchange', () => S.user && route());
function parseHash() {
  const h = location.hash.slice(1); const [p, q = ''] = h.split('?');
  return { path: p.split('/'), q: Object.fromEntries(new URLSearchParams(q)) };
}
async function route() {
  if (!S.user) return;
  S.rid++;
  const { path, q } = parseHash(); const k = path[0] || NAV[S.user.rol][0][0];
  $$('nav a').forEach(a => a.classList.toggle('on', a.dataset.k === k));
  const v = { dashboard, mantenedor, calificacion, carga, periodos, aprobaciones, auditoria, usuarios, tokens, respaldos }[k];
  const ok = NAV[S.user.rol].some(([n]) => n === k) || (k === 'calificacion' && S.user.rol !== 'AUDITOR');
  $('#view').innerHTML = ''; clearInterval(S.dash);
  if (!v || !ok) { $('#head').innerHTML = '<h1>Acceso no disponible</h1>'; $('#view').innerHTML = msg('err', 'No tiene permiso para ver esta sección.'); return; }
  alertas();
  try { await v(path, q); } catch (e) { fail($('#view'), e); }
}
const head = (t, s) => { $('#head').innerHTML = `<h1>${esc(t)}</h1><div class="sub">${esc(s || '')}</div>`; };
const opts = (list, sel) => list.map(([v, t]) => `<option value="${esc(v)}"${String(v) === String(sel ?? '') ? ' selected' : ''}>${esc(t)}</option>`).join('');
const periodosOpc = () => { const y = new Date().getFullYear(), l = []; for (let i = y + 1; i >= y - 4; i--) l.push([String(i), String(i)]); return l; };

/* ------------------------------------------------------------------ mantenedor (HU-1, HU-14, HU-16, HU-6) */
async function mantenedor(_, q) {
  const puede = ['CORREDOR', 'ADMIN'].includes(S.user.rol);
  head('Mantenedor de calificaciones', 'Filtre por mercado, origen y periodo comercial.');
  const pagina = +q.pagina || 1;
  $('#view').innerHTML = `<div class="card"><form id="ff" class="row">
    <label>País / mercado<select name="mercado">${opts([['', 'Todos'], ...Object.entries(PAIS)], q.mercado)}</select></label>
    <label>Origen<select name="origen">${opts([['', 'Todos'], ['bolsa', 'Bolsa'], ['corredor', 'Corredor'], ['sistema', 'Sistema']], q.origen)}</select></label>
    <label>Periodo comercial<select name="periodo">${opts([['', 'Todos'], ...periodosOpc()], q.periodo)}</select></label>
    <label>Declaración jurada<select name="tipo_dj">${opts([['', 'Todas'], ['1948', 'DJ 1948'], ['1922', 'DJ 1922']], q.tipo_dj)}</select></label>
    <label>Instrumento<input name="q" placeholder="Nombre del instrumento" value="${esc(q.q || '')}"></label>
    <label style="flex-direction:row;align-items:center;gap:6px"><input type="checkbox" name="pendientes" value="1" ${q.pendientes ? 'checked' : ''}> Solo pendientes</label>
    <button class="pri">Buscar</button><button type="button" id="lim">Limpiar</button></form></div>
  <div class="row" style="margin-bottom:10px">${puede ? '<button class="pri" id="nuevo">+ Nueva calificación</button><a class="btn" href="#carga">Carga masiva</a>' : ''}
  <button id="xx">Exportar Excel</button><button id="xp">Exportar PDF</button></div><div id="rb"></div><div id="rt"></div>`;
  const filt = () => { const f = new FormData($('#ff')); const p = new URLSearchParams(); for (const [k, v] of f) if (v) p.set(k, v); return p; };
  $('#ff').onsubmit = ev => { ev.preventDefault(); location.hash = '#mantenedor?' + filt(); };
  $('#lim').onclick = () => { location.hash = '#mantenedor'; };
  if (puede) $('#nuevo').onclick = () => { const p = filt(); p.delete('q'); p.delete('pendientes'); p.delete('origen'); location.hash = '#calificacion/new?' + p; };
  const exportar = async fmtx => {
    try {
      const p = filt(); p.set('format', fmtx); const r = await api('GET', '/api/v1/export?' + p, undefined, true);
      const b = await r.blob(), a = document.createElement('a'); a.href = URL.createObjectURL(b); a.download = `calificaciones.${fmtx}`; a.click(); URL.revokeObjectURL(a.href);
      toast(`Exportación ${fmtx.toUpperCase()} lista (${r.headers.get('X-Registros')} registros)`);
    } catch (e) { $('#rb').innerHTML = msg('warn', e.message); }
  };
  $('#xx').onclick = () => exportar('xlsx'); $('#xp').onclick = () => exportar('pdf');
  const p = new URLSearchParams(q); p.set('pagina', pagina);
  const d = await api('GET', '/api/v1/calificaciones?' + p);
  if (!d.filas.length) { $('#rt').innerHTML = '<div class="card empty">No se encontraron calificaciones con los filtros seleccionados.</div>'; return; }
  $('#rt').innerHTML = `<div class="tw"><table><thead><tr><th>Instrumento</th><th>País</th><th>Sec.</th><th>DJ</th><th>Periodo</th><th>Fecha pago</th><th>Descripción</th><th>Origen</th><th class="num">Σ 8–19</th><th>Estado</th><th></th></tr></thead><tbody>
  ${d.filas.map(r => `<tr><td><b>${esc(r.instrumento)}</b></td><td>${esc(r.pais)}</td><td>${r.secuencia}</td><td>${esc(r.tipo_dj)}</td><td>${esc(r.periodo)}</td><td>${esc(r.fecha_pago)}</td><td>${esc(r.descripcion)}</td>
  <td><span class="tag t-${esc(r.origen)}">${esc(r.origen)}</span></td><td class="num">${Number(r.suma_8_19).toFixed(8)}</td>
  <td><span class="tag t-${esc(r.estado)}">${esc(r.estado.replace('_', ' '))}</span>${r.discrepancia ? ' <span class="tag t-ERROR">discrepancia</span>' : ''}</td>
  <td style="white-space:nowrap"><a class="btn sm" href="#calificacion/${r.id}">${r.editable ? 'Editar' : 'Ver'}</a>${r.editable ? ` <button class="sm dg" data-del="${r.id}">Eliminar</button>` : ''}</td></tr>`).join('')}</tbody></table></div>
  <div class="pg"><span class="sub">${d.total} resultado(s) · página ${d.pagina}</span><button class="sm" id="pa" ${pagina <= 1 ? 'disabled' : ''}>←</button><button class="sm" id="ps" ${pagina * d.tam >= d.total ? 'disabled' : ''}>→</button></div>`;
  const go = n => { const x = new URLSearchParams(q); x.set('pagina', n); location.hash = '#mantenedor?' + x; };
  $('#pa').onclick = () => go(pagina - 1); $('#ps').onclick = () => go(pagina + 1);
  $$('[data-del]').forEach(b => b.onclick = async () => {
    if (!confirm('¿Eliminar esta calificación? Se conservan su historial de versiones y comentarios.')) return;
    try { await api('DELETE', '/api/v1/calificaciones/' + b.dataset.del); toast('Calificación eliminada'); route(); } catch (e) { fail($('#rb'), e); }
  });
}

/* ------------------------------------------------------------------ formulario de calificación (HU-2,3,4,10,13,17) */
const FACTORES = Array.from({ length: 29 }, (_, i) => i + 8);
const num = s => { s = String(s ?? '').trim().replace(/\s/g, ''); if (!s) return null; if (s.includes(',') && !s.includes('.')) s = s.replace(',', '.'); else s = s.replace(/,/g, ''); const n = Number(s); return Number.isFinite(n) ? n : NaN; };

async function calificacion(path, q) {
  const nuevo = path[1] === 'new', id = nuevo ? null : +path[1];
  let d = null;
  if (!nuevo) d = await api('GET', '/api/v1/calificaciones/' + id);
  const editable = nuevo ? ['CORREDOR', 'ADMIN'].includes(S.user.rol) : d.editable && d.periodoEstado !== 'CERRADO';
  const mercado = nuevo ? (q.mercado || 'CL') : d.mercado;
  const periodo = nuevo ? (q.periodo || String(new Date().getFullYear())) : d.periodo;
  head(nuevo ? 'Nueva calificación' : `Calificación #${id} · ${d.instrumento}`, nuevo ? 'Mercado y periodo se heredan de los filtros del mantenedor.' : `${d.pais} · origen ${d.origen} · estado ${d.estado}`);
  const fx = {}; (d ? d.factores : []).forEach(f => { fx[f.n] = f; });
  const dis = editable ? '' : 'disabled';
  $('#view').innerHTML = `<p><a href="#mantenedor?mercado=${esc(mercado)}&periodo=${esc(periodo)}">← Volver al mantenedor</a></p><div id="fb"></div>
  ${!nuevo && d.periodoEstado === 'CERRADO' ? msg('warn', 'El periodo está cerrado: la calificación es de solo lectura.') : ''}
  ${!nuevo && !d.editable ? msg('info', d.corredora_id == null && S.user.rol === 'CORREDOR' ? 'Dato de bolsa/sistema (solo lectura). Puede crear una versión local con prioridad para su corredora.' : 'Solo lectura.') : ''}
  ${!nuevo && !d.editable && d.corredora_id == null && S.user.rol === 'CORREDOR' ? '<p><button id="sobre" class="pri">Crear versión local</button></p>' : ''}
  <form id="cf"><div class="card"><h2>Datos básicos</h2><div class="fgrid">
    <label>País / mercado<select name="mercado" ${dis}>${opts(Object.entries(PAIS), mercado)}</select></label>
    <label>Periodo comercial (año)<input name="periodo" value="${esc(periodo)}" maxlength="4" ${dis} required></label>
    <label>Declaración jurada<select name="tipoDj" ${dis}>${opts([['1948', 'DJ 1948'], ['1922', 'DJ 1922']], d ? d.tipo_dj : (q.tipo_dj || '1948'))}</select></label>
    <label>Instrumento<input name="instrumento" value="${esc(d ? d.instrumento : '')}" maxlength="160" ${dis} required></label>
    <label>Secuencia<input name="secuencia" type="number" min="1" value="${esc(d ? d.secuencia : '')}" ${dis} required></label>
    <label>Fecha de pago<input name="fechaPago" type="date" value="${esc(d ? d.fecha_pago : '')}" ${dis} required></label>
    <label>Descripción<input name="descripcion" value="${esc(d ? d.descripcion : '')}" maxlength="400" ${dis} required></label>
    <label style="flex-direction:row;align-items:center;gap:6px"><input type="checkbox" name="acogidoIsfut" ${d && d.acogido_isfut ? 'checked' : ''} ${dis}> Acogido a ISFUT</label></div></div>
  <div class="card"><h2>Montos y factores (8 al 36)</h2>
    <div class="row" style="margin-bottom:8px"><button type="button" id="calc" class="pri" ${dis}>Calcular factores</button><span id="sum" class="sum ok">Σ 8–19 = 0</span></div><div id="cb"></div>
    <div class="fac${FACTORES.some(n => n > 19 && fx[n]) ? '' : ' hide'}" id="fac"><table><thead><tr><th>Factor</th><th>Monto</th><th>Valor (factor)</th></tr></thead><tbody>
    ${FACTORES.map(n => `<tr${n > 19 ? ' class="ext"' : ''}><td>${n}${n <= 19 ? ' *' : ''}</td><td><input id="m${n}" inputmode="decimal" value="${esc(fx[n] && fx[n].monto != null ? fx[n].monto : '')}" ${dis}></td><td><input id="v${n}" inputmode="decimal" value="${esc(fx[n] && fx[n].valor != null ? fx[n].valor : '')}" ${dis}></td></tr>`).join('')}
    </tbody></table></div><p><button type="button" id="mas" class="sm"></button></p><p class="sub">* Los factores 8 al 19 deben informarse para calcular y su suma no puede superar 1.</p></div>
  ${editable ? `<div class="card"><label>Comentario (opcional)<textarea name="comentario" rows="2" maxlength="2000"></textarea></label></div>
  <div class="row"><button class="pri" id="save">Guardar</button><button type="button" id="cancel">Cancelar</button></div>` : ''}</form>
  ${nuevo ? '' : '<div id="extra"></div>'}`;
  const sumar = () => { let s = 0; for (let n = 8; n <= 19; n++) { const v = num($('#v' + n).value); if (v) s += v; } return s; };
  const refrescar = () => {
    const s = sumar(), el = $('#sum'), mal = s > 1 + 1e-12;
    el.className = 'sum ' + (mal ? 'bad' : 'ok');
    el.textContent = mal ? `Σ 8–19 = ${s.toFixed(8)} — supera 1. Corrija los factores.` : `Σ 8–19 = ${s.toFixed(8)}`;
    if ($('#save')) $('#save').disabled = mal;
    if ($('#calc')) $('#calc').disabled = !editable || FACTORES.filter(n => n <= 19).some(n => !String($('#m' + n).value).trim());
  };
  const tog = () => { $('#mas').textContent = $('#fac').classList.contains('hide') ? 'Mostrar factores 20 al 36' : 'Ocultar factores 20 al 36'; };
  $('#mas').onclick = () => { $('#fac').classList.toggle('hide'); tog(); }; tog();
  FACTORES.forEach(n => { $('#m' + n).oninput = refrescar; $('#v' + n).oninput = refrescar; }); refrescar();
  if (!editable) FACTORES.forEach(n => { $('#m' + n).disabled = $('#v' + n).disabled = true; });
  const marcar = campo => { $$('input.bad').forEach(i => i.classList.remove('bad')); const m = /^(monto|valor)_(\d+)$/.exec(campo || ''); if (m) { const el = $((m[1] === 'monto' ? '#m' : '#v') + m[2]); if (el) { el.classList.add('bad'); el.scrollIntoView({ block: 'center' }); } } else if (campo) { const el = $(`[name=${campo}]`); if (el) el.classList.add('bad'); } };
  if ($('#calc')) $('#calc').onclick = async () => {
    const montos = {}; FACTORES.forEach(n => { const v = num($('#m' + n).value); if (v !== null) montos[n] = v; });
    try { const r = await api('POST', '/api/v1/calcular', { mercado: $('[name=mercado]').value, montos }); $('#cb').innerHTML = '';
      FACTORES.forEach(n => { $('#v' + n).value = r.factores[n] != null ? r.factores[n] : ''; }); refrescar(); if (r.aviso) $('#cb').innerHTML = msg('warn', r.aviso); else toast('Factores calculados'); }
    catch (e) { $('#cb').innerHTML = msg('err', e.message); marcar(e.data.campo); }
  };
  if ($('#cancel')) $('#cancel').onclick = () => { location.hash = `#mantenedor?mercado=${mercado}&periodo=${periodo}`; };
  if ($('#sobre')) $('#sobre').onclick = async () => { try { const r = await api('POST', `/api/v1/calificaciones/${id}/sobrescribir`, {}); toast('Versión local creada'); location.hash = '#calificacion/' + r.id; } catch (e) { fail($('#fb'), e); } };
  if (editable) $('#cf').onsubmit = async ev => {
    ev.preventDefault(); $('#fb').innerHTML = ''; marcar(null);
    const f = new FormData(ev.target), factores = {};
    for (const n of FACTORES) { const m = num($('#m' + n).value), v = num($('#v' + n).value);
      if (Number.isNaN(m)) { $('#fb').innerHTML = msg('err', `El monto del factor ${n} no es numérico`); return marcar('monto_' + n); }
      if (Number.isNaN(v)) { $('#fb').innerHTML = msg('err', `El factor ${n} no es numérico`); return marcar('valor_' + n); }
      if (m !== null || v !== null) factores[n] = { monto: m, valor: v }; }
    const body = { mercado: f.get('mercado'), periodo: f.get('periodo'), instrumento: f.get('instrumento'), secuencia: f.get('secuencia'), tipoDj: f.get('tipoDj'), fechaPago: f.get('fechaPago'), descripcion: f.get('descripcion'), acogidoIsfut: f.has('acogidoIsfut'), comentario: f.get('comentario'), factores };
    const enviar = async conf => { body.confirmarDuplicado = conf; return nuevo ? api('POST', '/api/v1/calificaciones', body) : api('PUT', '/api/v1/calificaciones/' + id, body); };
    try { let r; try { r = await enviar(false); } catch (e) { if (e.status === 409 && e.data.duplicado && confirm(e.message + '\n\n¿Desea guardarla de todos modos?')) r = await enviar(true); else throw e; }
      toast('Calificación guardada'); if (nuevo) location.hash = '#calificacion/' + r.id; else route(); }
    catch (e) { $('#fb').innerHTML = msg('err', e.message); marcar(e.data.campo); window.scrollTo(0, 0); }
  };
  if (!nuevo) extras(id, d, editable);
}

async function extras(id, d, editable) {
  const [cm, vs] = await Promise.all([api('GET', `/api/v1/calificaciones/${id}/comentarios`), api('GET', `/api/v1/calificaciones/${id}/versiones`)]);
  const k = d.conciliacion;
  $('#extra').innerHTML = `<details class="card" ${cm.length ? 'open' : ''}><summary><b>Comentarios e historial de versiones</b></summary><div class="grid2" style="margin-top:12px"><div><h2>Comentarios</h2><div id="cl">${cm.length ? cm.map(c => `<div class="cm"><small>${esc(c.usuario)} · ${fmt(c.fecha)}</small><br>${esc(c.texto)}</div>`).join('') : '<span class="sub">Sin comentarios.</span>'}</div>
    ${['CORREDOR', 'SUPERVISOR', 'ADMIN'].includes(S.user.rol) ? '<form id="cmf" class="row" style="margin-top:8px"><input name="t" placeholder="Agregar comentario" style="flex:1" maxlength="2000"><button>Agregar</button></form>' : ''}</div>
  <div><h2>Historial de versiones</h2>${vs.length ? `<table><thead><tr><th>Ver.</th><th>Acción</th><th>Usuario</th><th>Fecha</th><th></th></tr></thead><tbody>${vs.map(v => `<tr><td>${v.version}</td><td>${esc(v.accion)}</td><td>${esc(v.usuario)}</td><td>${fmt(v.fecha)}</td><td><button class="sm" data-v="${v.version}">Ver</button></td></tr>`).join('')}</tbody></table>` : '<span class="sub">Sin versiones.</span>'}</div></div></details>
  <details class="card" ${k && k.estado === 'DISCREPANCIA' ? 'open' : ''}><summary><b>Conciliación certificado del emisor vs. DJ del SII</b></summary><div id="kb" style="margin-top:12px"></div>
  ${k ? `<p>Última conciliación: <span class="tag t-${k.estado === 'COINCIDE' || k.estado === 'RESUELTA' ? 'OK' : 'ERROR'}">${esc(k.estado)}</span>${k.fuente_usada ? ` · fuente usada: ${esc(k.fuente_usada)} · motivo: ${esc(k.motivo)}` : ''}</p>${k.diferencias ? `<pre>${esc(k.diferencias)}</pre>` : ''}` : ''}
  ${editable ? `${k && k.estado === 'DISCREPANCIA' ? `<form id="rf" class="row"><label>Fuente utilizada<select name="fuente"><option value="CERTIFICADO">Certificado del emisor</option><option value="DJ">DJ del SII</option></select></label><label style="flex:1">Motivo<input name="motivo" required></label><button class="pri">Resolver</button></form>` : ''}
  <form id="kf"><div class="grid2"><label>Certificado del emisor (una línea "factor;monto")<textarea name="cert" rows="4" placeholder="8;100000"></textarea></label><label>Declaración jurada SII ("factor;monto")<textarea name="dj" rows="4" placeholder="8;100000"></textarea></label></div><button class="pri" style="margin-top:8px">Comparar</button></form>` : ''}</details>`;
  if ($('#cmf')) $('#cmf').onsubmit = async ev => { ev.preventDefault(); try { await api('POST', `/api/v1/calificaciones/${id}/comentarios`, { texto: new FormData(ev.target).get('t') }); route(); } catch (e) { toast(e.message); } };
  $$('[data-v]').forEach(b => b.onclick = async () => {
    const v = await api('GET', `/api/v1/calificaciones/${id}/versiones/${b.dataset.v}`), s = v.datos, m = document.createElement('div'); m.className = 'modal';
    m.innerHTML = `<div class="box"><h2>Versión ${v.version} · ${esc(v.accion)}</h2><p class="sub">${esc(v.usuario)} · ${fmt(v.fecha)} · solo lectura (no modifica la versión actual)</p>
    <p><b>${esc(s.instrumento)}</b> · sec ${esc(s.secuencia)} · periodo ${esc(s.periodo)} · ${esc(s.fecha_pago)} · estado ${esc(s.estado)}<br>${esc(s.descripcion)}</p>
    <table><thead><tr><th>Factor</th><th class="num">Monto</th><th class="num">Valor</th></tr></thead><tbody>${(s.factores || []).map(f => `<tr><td>${f.n}</td><td class="num">${fnum(f.monto)}</td><td class="num">${esc(f.valor ?? '')}</td></tr>`).join('')}</tbody></table><p><button id="mc">Cerrar</button></p></div>`;
    document.body.append(m); $('#mc', m).onclick = () => m.remove();
  });
  if ($('#kf')) $('#kf').onsubmit = async ev => { ev.preventDefault(); const f = new FormData(ev.target);
    try { const r = await api('POST', `/api/v1/calificaciones/${id}/conciliar`, { certificado: f.get('cert'), dj: f.get('dj') });
      if (r.estado === 'COINCIDE') { $('#kb').innerHTML = msg('ok', r.mensaje); } else { $('#kb').innerHTML = msg('warn', r.mensaje) + `<pre>${esc(r.diferencias.join('\n'))}</pre>`; setTimeout(route, 1800); } }
    catch (e) { fail($('#kb'), e); } };
  if ($('#rf')) $('#rf').onsubmit = async ev => { ev.preventDefault(); const f = new FormData(ev.target);
    try { await api('POST', `/api/v1/calificaciones/${id}/conciliar/resolver`, { fuente: f.get('fuente'), motivo: f.get('motivo') }); toast('Discrepancia resuelta: recalcule los factores'); route(); } catch (e) { fail($('#kb'), e); } };
}

/* ------------------------------------------------------------------ carga masiva e integración SII (RF-05, HU-15) */
async function carga() {
  head('Carga masiva', 'Cargue calificaciones desde un archivo CSV o Excel (.xlsx), o impórtelas desde el SII.');
  $('#view').innerHTML = `<div class="grid2"><div class="card"><h2>Archivo</h2><div id="cb"></div><form id="cf" class="row" style="flex-direction:column;align-items:stretch">
    <label>Archivo (.csv / .xlsx)<input type="file" name="f" accept=".csv,.xlsx" required></label>
    <label>Periodo por defecto (opcional)<select name="periodo">${opts([['', '—'], ...periodosOpc()])}</select></label>
    <label>Mercado por defecto (opcional)<select name="mercado">${opts([['', '—'], ...Object.entries(PAIS)])}</select></label>
    <div class="row"><button class="pri">Procesar</button><a class="btn" href="/api/v1/plantilla.csv" id="pl">Descargar plantilla</a></div></form></div>
  <div class="card"><h2>Importar declaraciones juradas del SII</h2><div id="sb"></div>${S.user.rol === 'CORREDOR' ? `<form id="sf" class="row"><label>Periodo<select name="periodo">${opts(periodosOpc(), String(new Date().getFullYear() - 1))}</select></label>
    <label style="flex-direction:row;align-items:center;gap:6px"><input type="checkbox" name="caida"> Simular caída del SII</label><button class="pri">Importar</button></form>` : '<p class="sub">Disponible para corredores.</p>'}</div></div>
  <div id="res"></div><h2>Historial de cargas</h2><div id="hist"></div>`;
  $('#pl').onclick = async ev => { ev.preventDefault(); const r = await api('GET', '/api/v1/plantilla.csv', undefined, true), a = document.createElement('a'); a.href = URL.createObjectURL(await r.blob()); a.download = 'plantilla_carga_masiva.csv'; a.click(); };
  const resultado = r => {
    const det = r.detalle || [], err = det.filter(x => x.estado !== 'OK');
    $('#res').innerHTML = `<div class="card"><h2>Resultado</h2>${msg(r.errores ? 'warn' : 'ok', `Filas: ${r.total ?? det.length} · correctas: ${r.ok ?? ''} · con error/revisión: ${r.errores ?? err.length}${r.estado ? ' · ' + r.estado : ''}`)}
    ${det.length ? `<div class="tw"><table><thead><tr><th>Fila</th><th>Sec.</th><th>Estado</th><th>Motivo</th></tr></thead><tbody>${det.map(x => `<tr><td>${x.fila}</td><td>${esc(x.secuencia ?? '')}</td><td><span class="tag t-${esc(x.estado)}">${esc(x.estado)}</span></td><td>${esc(x.motivo ?? '')}</td></tr>`).join('')}</tbody></table></div>` : ''}</div>`;
  };
  $('#cf').onsubmit = async ev => {
    ev.preventDefault(); const f = new FormData(ev.target), file = f.get('f');
    const b64 = await new Promise((ok, no) => { const fr = new FileReader(); fr.onload = () => ok(String(fr.result).split(',')[1]); fr.onerror = no; fr.readAsDataURL(file); });
    try { const r = await api('POST', '/api/v1/carga-masiva', { nombre: file.name, contenidoBase64: b64, periodo: f.get('periodo') || null, mercado: f.get('mercado') || null }); $('#cb').innerHTML = ''; resultado(r); hist(); }
    catch (e) { fail($('#cb'), e); }
  };
  if ($('#sf')) $('#sf').onsubmit = async ev => {
    ev.preventDefault(); const f = new FormData(ev.target);
    try { const r = await api('POST', '/api/v1/sii/importar', { periodo: f.get('periodo'), simularCaida: f.has('caida') }); $('#sb').innerHTML = msg('ok', 'Importación del SII finalizada.'); resultado(r); hist(); }
    catch (e) { $('#sb').innerHTML = msg('err', e.message + (e.status >= 500 || e.status === 503 ? ' Puede ingresar los datos manualmente o por archivo.' : '')); }
  };
  async function hist() {
    const h = await api('GET', '/api/v1/carga-masiva');
    $('#hist').innerHTML = h.length ? `<div class="tw"><table><thead><tr><th>#</th><th>Tipo</th><th>Archivo</th><th>Estado</th><th>Total</th><th>OK</th><th>Errores</th><th>Fecha</th><th>Usuario</th></tr></thead><tbody>${h.map(x => `<tr><td>${x.id}</td><td>${esc(x.tipo)}</td><td>${esc(x.nombre_archivo)}</td><td><span class="tag t-${esc(x.estado)}">${esc(x.estado)}</span></td><td>${x.total}</td><td>${x.ok}</td><td>${x.errores}</td><td>${fmt(x.fecha)}</td><td>${esc(x.usuario)}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty">Sin cargas todavía.</div>';
  }
  hist();
}

/* ------------------------------------------------------------------ dashboard (HU-9) */
async function dashboard(_, q) {
  head('Dashboard', 'Avance de calificaciones por periodo comercial (se actualiza automáticamente).');
  const pintar = async () => {
    const per = $('#dp') ? $('#dp').value : (q.periodo || ''), d = await api('GET', '/api/v1/dashboard' + (per ? '?periodo=' + encodeURIComponent(per) : ''));
    const pct = d.total ? Math.round(100 * (d.total - d.pendientes) / d.total) : 0;
    $('#view').innerHTML = `<div class="row" style="margin-bottom:12px"><label>Periodo comercial<select id="dp">${opts((d.periodos.length ? d.periodos : [d.periodo]).map(x => [x, x]), d.periodo)}</select></label><span class="sub">Actualizado ${esc(d.actualizado)}</span></div>
    ${d.vacio ? '<div class="card empty">No hay calificaciones registradas en este periodo comercial.</div>' : `<div class="cards"><div class="kpi"><b>${d.total}</b><span>Ingresadas</span></div><div class="kpi pend"><b>${d.pendientes}</b><span>Pendientes</span></div><div class="kpi ok"><b>${d.completas}</b><span>Completas</span></div><div class="kpi ok"><b>${d.aprobadas}</b><span>Aprobadas</span></div><div class="kpi err"><b>${d.errores.total}</b><span>Con errores de validación</span></div></div>
    <div class="card"><h2>Avance del periodo ${esc(d.periodo)}: ${pct}%</h2><div class="bar"><i style="width:${pct}%"></i></div></div>
    <div class="card"><h2>Por país</h2><table><tbody>${d.porMercado.map(m => `<tr><td>${esc(m.pais)}</td><td class="num">${m.total}</td></tr>`).join('')}</tbody></table></div>`}`;
    $('#dp').onchange = () => { q.periodo = $('#dp').value; pintar(); };
  };
  await pintar(); S.dash = setInterval(() => pintar().catch(() => { }), 15000);
}

/* ------------------------------------------------------------------ periodos y aprobaciones (HU-7, HU-18) */
async function periodos() {
  head('Periodos comerciales', 'Plazo de la DJ 1948, cierre y aprobación.');
  const l = await api('GET', '/api/v1/periodos'), rol = S.user.rol;
  $('#view').innerHTML = `<div id="pb"></div><div class="tw"><table><thead><tr><th>Corredora</th><th>Periodo</th><th>Estado</th><th>Límite DJ 1948</th><th>Días</th><th>Total</th><th>Pendientes</th><th>Aprobadas</th><th></th></tr></thead><tbody>${l.map(p => `<tr><td>${esc(p.corredora)}</td><td><b>${esc(p.periodo)}</b></td><td><span class="tag t-${esc(p.estado)}">${esc(p.estado)}</span></td><td>${esc(p.fecha_limite)}</td><td class="${p.vencido ? 'bad' : ''}">${p.vencido ? 'vencido (' + (-p.dias_restantes) + ')' : p.dias_restantes}</td><td>${p.total}</td><td>${p.pendientes}</td><td>${p.aprobadas}</td>
  <td style="white-space:nowrap">${p.pendientes ? `<a class="btn sm" href="#mantenedor?periodo=${esc(p.periodo)}&pendientes=1">Ver pendientes</a> ` : ''}${['CORREDOR', 'SUPERVISOR'].includes(rol) && p.estado === 'ABIERTO' ? `<button class="sm" data-sc="${p.id}">Solicitar cierre</button>` : ''}${rol === 'ADMIN' ? `<button class="sm" data-lim="${p.id}">Fijar límite</button>${p.estado === 'CERRADO' ? ` <button class="sm dg" data-re="${p.id}">Reabrir</button>` : ''}` : ''}</td></tr>`).join('') || '<tr><td colspan="9" class="empty">Sin periodos.</td></tr>'}</tbody></table></div>`;
  const run = async (fn, ok) => { try { await fn(); toast(ok); route(); } catch (e) { fail($('#pb'), e); } };
  $$('[data-sc]').forEach(b => b.onclick = () => run(() => api('POST', `/api/v1/periodos/${b.dataset.sc}/solicitar-cierre`, {}), 'Cierre solicitado: queda pendiente de aprobación del supervisor'));
  $$('[data-lim]').forEach(b => b.onclick = () => { const f = prompt('Nueva fecha límite (AAAA-MM-DD):'); if (f) run(() => api('PUT', `/api/v1/periodos/${b.dataset.lim}/limite`, { fecha: f }), 'Fecha límite actualizada'); });
  $$('[data-re]').forEach(b => b.onclick = () => { const m = prompt('Motivo de la reapertura:'); if (m) run(() => api('POST', `/api/v1/periodos/${b.dataset.re}/reabrir`, { motivo: m }), 'Periodo reabierto'); });
}
async function aprobaciones() {
  head('Aprobaciones', 'Revise las calificaciones de los periodos en revisión.');
  const l = await api('GET', '/api/v1/aprobaciones');
  $('#view').innerHTML = `<div id="ab"></div>${l.length ? `<div class="tw"><table><thead><tr><th>Instrumento</th><th>País</th><th>Sec.</th><th>Periodo</th><th>Ingresado por</th><th class="num">Σ 8–19</th><th>Estado</th><th></th></tr></thead><tbody>${l.map(r => `<tr><td><a href="#calificacion/${r.id}">${esc(r.instrumento)}</a></td><td>${esc(r.pais)}</td><td>${r.secuencia}</td><td>${esc(r.periodo)}</td><td>${esc(r.ingresado_por)}</td><td class="num">${Number(r.suma_8_19).toFixed(8)}</td><td><span class="tag t-${esc(r.estado)}">${esc(r.estado)}</span></td><td style="white-space:nowrap"><button class="sm pri" data-ap="${r.id}">Aprobar</button> <button class="sm dg" data-rc="${r.id}">Rechazar</button></td></tr>`).join('')}</tbody></table></div>` : '<div class="card empty">No hay calificaciones pendientes de aprobación.</div>'}`;
  $$('[data-ap]').forEach(b => b.onclick = async () => { try { const r = await api('POST', `/api/v1/calificaciones/${b.dataset.ap}/aprobar`, {}); toast(r.periodoCerrado ? 'Aprobada. El periodo quedó cerrado.' : `Aprobada. Faltan ${r.faltanPorAprobar} por aprobar.`); route(); } catch (e) { fail($('#ab'), e); } });
  $$('[data-rc]').forEach(b => b.onclick = async () => { const m = prompt('Motivo del rechazo (obligatorio):'); if (!m) return; try { await api('POST', `/api/v1/calificaciones/${b.dataset.rc}/rechazar`, { motivo: m }); toast('Rechazada y notificada al corredor'); route(); } catch (e) { fail($('#ab'), e); } });
}

/* ------------------------------------------------------------------ auditoría (HU-5) */
async function auditoria(_, q) {
  head('Auditoría', 'Registro inmutable de acciones: solo lectura.');
  const p = new URLSearchParams(q); const pagina = +q.pagina || 1; p.set('pagina', pagina);
  const d = await api('GET', '/api/v1/auditoria?' + p);
  $('#view').innerHTML = `<div class="card"><form id="af" class="row"><label>Usuario<input name="usuario" value="${esc(q.usuario || '')}"></label>
  <label>Acción<select name="accion">${opts([['', 'Todas'], ...d.acciones.map(a => [a, a])], q.accion)}</select></label>
  <label>Entidad<input name="entidad" value="${esc(q.entidad || '')}" placeholder="calificacion"></label>
  <label>Origen<select name="origen">${opts([['', 'Todos'], ['WEB', 'WEB'], ['API', 'API'], ['SISTEMA', 'SISTEMA'], ['CARGA_MASIVA', 'CARGA_MASIVA'], ['SII', 'SII']], q.origen)}</select></label>
  <label>Registro #<input name="registro" type="number" value="${esc(q.registro || '')}" style="width:90px"></label>
  <label>Desde<input type="date" name="desde" value="${esc(q.desde || '')}"></label><label>Hasta<input type="date" name="hasta" value="${esc(q.hasta || '')}"></label><button class="pri">Filtrar</button></form></div>
  <div class="tw"><table><thead><tr><th>Fecha</th><th>Usuario</th><th>Acción</th><th>Entidad</th><th>Reg.</th><th>Origen</th><th>Detalle</th></tr></thead><tbody>${d.filas.map(r => `<tr><td style="white-space:nowrap">${fmt(r.fecha)}</td><td>${esc(r.usuario || '—')}</td><td><b>${esc(r.accion)}</b></td><td>${esc(r.entidad || '')}</td><td>${esc(r.registro ?? '')}</td><td>${esc(r.origen || '')}</td><td>${esc(r.detalle || '')}</td></tr>`).join('') || '<tr><td colspan="7" class="empty">Sin registros.</td></tr>'}</tbody></table></div>
  <div class="pg"><span class="sub">${d.total} registro(s)</span><button class="sm" id="pa" ${pagina <= 1 ? 'disabled' : ''}>←</button><button class="sm" id="ps" ${pagina * d.tam >= d.total ? 'disabled' : ''}>→</button></div>`;
  $('#af').onsubmit = ev => { ev.preventDefault(); const x = new URLSearchParams(); for (const [k, v] of new FormData(ev.target)) if (v) x.set(k, v); location.hash = '#auditoria?' + x; };
  const go = n => { const x = new URLSearchParams(q); x.set('pagina', n); location.hash = '#auditoria?' + x; };
  $('#pa').onclick = () => go(pagina - 1); $('#ps').onclick = () => go(pagina + 1);
}

/* ------------------------------------------------------------------ usuarios (HU-8), tokens (HU-19), respaldos (HU-11) */
async function usuarios() {
  head('Usuarios y roles', 'Un usuario sin rol no puede iniciar sesión. Los cambios de rol aplican al próximo inicio de sesión.');
  const [todos, cs] = await Promise.all([api('GET', '/api/v1/usuarios'), api('GET', '/api/v1/corredoras')]);
  const verBajas = sessionStorage.getItem('verBajas') === '1', us = todos.filter(u => u.activo || verBajas), nBajas = todos.length - todos.filter(u => u.activo).length;
  const roles = [['', '(sin rol)'], ['ADMIN', 'Administrador'], ['CORREDOR', 'Corredor'], ['SUPERVISOR', 'Supervisor'], ['AUDITOR', 'Auditor']];
  $('#view').innerHTML = `<div id="ub"></div><div class="card"><h2>Nuevo usuario</h2><form id="nf" class="row"><label>Nombre<input name="nombre" required></label><label>Correo<input name="correo" type="email" required></label>
  <label>Contraseña<input name="password" type="password" required minlength="8"></label><label>Rol<select name="rol">${opts(roles)}</select></label><label>Corredora<select name="corredoraId">${opts([['', '—'], ...cs.map(c => [c.id, c.nombre])])}</select></label><button class="pri">Crear</button></form></div>
  ${nBajas ? `<p><label style="flex-direction:row;align-items:center;gap:6px"><input type="checkbox" id="vb" ${verBajas ? 'checked' : ''}> Mostrar usuarios dados de baja (${nBajas})</label></p>` : ''}<div class="tw"><table><thead><tr><th>Nombre</th><th>Correo</th><th>Rol</th><th>Corredora</th><th>2FA</th><th>Activo</th><th>Último acceso</th><th></th></tr></thead><tbody>${us.map(u => `<tr><td>${esc(u.nombre)}</td><td>${esc(u.correo)}</td>
  <td><select data-r="${u.id}">${opts(roles, u.rol || '')}</select></td><td>${esc(u.corredora || '—')}</td><td>${u.totp_activo ? 'sí' : 'no'}</td><td>${u.activo ? 'sí' : 'no'}</td><td>${fmt(u.ultimo_login)}</td>
  <td style="white-space:nowrap"><button class="sm" data-a="${u.id}" data-v="${u.activo}">${u.activo ? 'Desactivar' : 'Activar'}</button> <button class="sm" data-f="${u.id}">Reset 2FA</button> <button class="sm" data-p="${u.id}">Clave</button> <button class="sm dg" data-d="${u.id}">Eliminar</button></td></tr>`).join('')}</tbody></table></div>`;
  const run = async (fn, ok) => { try { await fn(); toast(ok); route(); } catch (e) { fail($('#ub'), e); } };
  if ($('#vb')) $('#vb').onchange = () => { sessionStorage.setItem('verBajas', $('#vb').checked ? '1' : '0'); route(); };
  $('#nf').onsubmit = ev => { ev.preventDefault(); const f = Object.fromEntries(new FormData(ev.target)); f.rol = f.rol || null; f.corredoraId = f.corredoraId ? +f.corredoraId : null; run(() => api('POST', '/api/v1/usuarios', f), 'Usuario creado'); };
  $$('[data-r]').forEach(s => s.onchange = () => run(() => api('PUT', '/api/v1/usuarios/' + s.dataset.r, { rol: s.value || null }), 'Rol actualizado (aplica al próximo inicio de sesión)'));
  $$('[data-a]').forEach(b => b.onclick = () => run(() => api('PUT', '/api/v1/usuarios/' + b.dataset.a, { activo: b.dataset.v !== 'true' }), 'Usuario actualizado'));
  $$('[data-f]').forEach(b => b.onclick = () => confirm('¿Restablecer el 2FA de este usuario?') && run(() => api('POST', `/api/v1/usuarios/${b.dataset.f}/reset2fa`, {}), '2FA restablecido'));
  $$('[data-p]').forEach(b => b.onclick = () => { const p = prompt('Nueva contraseña (mín. 8 caracteres):'); if (p) run(() => api('POST', `/api/v1/usuarios/${b.dataset.p}/password`, { password: p }), 'Contraseña actualizada'); });
  $$('[data-d]').forEach(b => b.onclick = () => confirm('¿Eliminar usuario? Sus calificaciones se conservan.') && run(() => api('DELETE', '/api/v1/usuarios/' + b.dataset.d), 'Usuario eliminado'));
}
async function tokens() {
  head('API REST', 'Acceso programático: Authorization: Bearer <token> sobre /api/v1/calificaciones (límite por minuto).');
  const l = await api('GET', '/api/v1/tokens');
  $('#view').innerHTML = `<div id="tb"></div><div class="card"><form id="tf" class="row"><label>Nombre del token<input name="nombre" required></label><button class="pri">Generar token</button></form></div>
  <div class="tw"><table><thead><tr><th>Nombre</th><th>Estado</th><th>Creado</th><th>Último uso</th><th></th></tr></thead><tbody>${l.map(t => `<tr><td>${esc(t.nombre)}</td><td>${t.activo ? 'activo' : 'revocado'}</td><td>${fmt(t.creado_en)}</td><td>${fmt(t.ultimo_uso)}</td><td>${t.activo ? `<button class="sm dg" data-x="${t.id}">Revocar</button>` : ''}</td></tr>`).join('') || '<tr><td colspan="5" class="empty">Sin tokens.</td></tr>'}</tbody></table></div>
  <div class="card" style="margin-top:14px"><h2>Ejemplo</h2><pre>curl -H "Authorization: Bearer nuam_..." ${esc(location.origin)}/api/v1/calificaciones?periodo=2026</pre></div>`;
  $('#tf').onsubmit = async ev => { ev.preventDefault(); try { const r = await api('POST', '/api/v1/tokens', { nombre: new FormData(ev.target).get('nombre') }); $('#tb').innerHTML = msg('warn', r.aviso) + `<div class="secret">${esc(r.token)}</div>`; const l2 = await api('GET', '/api/v1/tokens'); void l2; } catch (e) { fail($('#tb'), e); } };
  $$('[data-x]').forEach(b => b.onclick = async () => { if (confirm('¿Revocar token?')) { await api('DELETE', '/api/v1/tokens/' + b.dataset.x); route(); } });
}
async function respaldos() {
  head('Respaldos', 'Copia diaria cifrada (23:55). Retención configurable (30 días por defecto).');
  const l = await api('GET', '/api/v1/respaldos');
  $('#view').innerHTML = `<div id="bb"></div><p><button class="pri" id="rn">Respaldar ahora</button></p><div class="tw"><table><thead><tr><th>#</th><th>Archivo</th><th>Tipo</th><th>Estado</th><th>Inicio</th><th>Tamaño</th><th>Mensaje</th><th></th></tr></thead><tbody>${l.map(r => `<tr><td>${r.id}</td><td>${esc(r.archivo || '')}</td><td>${esc(r.tipo)}</td><td><span class="tag t-${esc(r.estado)}">${esc(r.estado)}</span></td><td>${fmt(r.iniciado)}</td><td class="num">${r.bytes ?? ''}</td><td>${esc(r.mensaje || '')}</td><td>${r.estado === 'OK' ? `<button class="sm dg" data-r="${r.id}">Restaurar</button>` : ''}</td></tr>`).join('') || '<tr><td colspan="8" class="empty">Sin respaldos.</td></tr>'}</tbody></table></div>`;
  $('#rn').onclick = async () => { $('#rn').disabled = true; try { const r = await api('POST', '/api/v1/respaldos', {}); toast(r.estado === 'OK' || r.ok ? 'Respaldo completado' : 'Respaldo finalizado'); } catch (e) { $('#bb').innerHTML = msg('err', e.message); } route(); };
  $$('[data-r]').forEach(b => b.onclick = async () => { if (!confirm('Esto reemplaza los datos actuales por los del respaldo. ¿Continuar?')) return; try { await api('POST', `/api/v1/respaldos/${b.dataset.r}/restaurar`, {}); toast('Base de datos restaurada'); } catch (e) { fail($('#bb'), e); } });
}

/* ------------------------------------------------------------------ arranque */
(async () => {
  try { entrar(await api('GET', '/auth/me')); } catch (e) { showLogin(); }
})();
