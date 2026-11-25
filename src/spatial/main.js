import { DEFAULT_SETTINGS, PATHS } from './defaults.js';
import { BUILTIN_PRESETS, EQ_PRESETS, ROOM_PRESETS, makePreset } from './presets.js';
import { SpatialBridge } from './bridge.js';

const clone = value => JSON.parse(JSON.stringify(value));
const STORAGE_KEY = 'ymusic-lite-spatial-settings-v1';
const CUSTOM_KEY = 'ymusic-lite-spatial-presets-v1';
let settings = {...DEFAULT_SETTINGS};
let nativeState = null;
let savedPresets = [];
let lastFrame = null;
let saveTimer = null;
let toastTimer = null;
let drawPathMode = false;
let draftPath = [];
let rootEl = document;
const $ = id => (rootEl.getElementById ? rootEl.getElementById(id) : null) || document.getElementById(id);
const on = (id, ev, fn) => { const el = $(id); if (el) el.addEventListener(ev, fn); return el; };
const num = (v, d=1) => Number(v).toFixed(d);

function toast(message) {
  const el = $('toast'); el.textContent = message; el.classList.add('show');
  clearTimeout(toastTimer); toastTimer = setTimeout(()=>el.classList.remove('show'), 2200);
}
function storeLocal() {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(settings)); $('save-status').textContent='Pengaturan tersimpan lokal'; }
  catch { $('save-status').textContent='Penyimpanan lokal tidak tersedia'; }
}
function loadLocal() {
  try { const v=JSON.parse(localStorage.getItem(STORAGE_KEY)||'null'); if(v&&typeof v==='object') settings={...DEFAULT_SETTINGS,...v,eq:Array.isArray(v.eq)&&v.eq.length===10?v.eq:DEFAULT_SETTINGS.eq}; }
  catch { settings={...DEFAULT_SETTINGS}; }
  try { savedPresets=JSON.parse(localStorage.getItem(CUSTOM_KEY)||'[]'); if(!Array.isArray(savedPresets))savedPresets=[]; }
  catch { savedPresets=[]; }
}
function persistSettings() {
  storeLocal(); clearTimeout(saveTimer);
  saveTimer=setTimeout(async()=>{
    try { await SpatialBridge.setSettings(settings); }
    catch(err){ console.warn('Spatial native bridge:',err); $('save-status').textContent='Gagal sinkron ke engine native'; }
  },180);
}
function setField(key,value,{sync=true,render=false}={}) {
  settings[key]=value;
  if(sync) persistSettings();
  if(render) renderAll();
  drawOrbit(); updateReadout();
}
function control(key,label,min,max,step,unit='',digits=1,hint='') {
  const value=Number(settings[key] ?? min);
  const shown = unit==='%' ? value*100 : value;
  const out = unit==='dB' ? `${value>0?'+':''}${num(value,digits)} dB` : `${num(shown,digits)}${unit}`;
  return `<div class="control"><div class="control-head"><label for="c-${key}">${label}</label><output id="o-${key}">${out}</output></div><input id="c-${key}" data-key="${key}" type="range" min="${min}" max="${max}" step="${step}" value="${value}"><div class="control-hint">${hint}</div></div>`;
}
function choice(key, options) {
  const current=settings[key];
  const chosen=typeof current==='boolean'?(current?0:1):Number(current||0);
  return `<div class="choice-row" data-choice-key="${key}">${options.map((v,i)=>`<button class="choice ${chosen===i?'active':''}" data-choice-value="${i}">${v}</button>`).join('')}</div>`;
}
function renderPaths() {
  $('path-grid').innerHTML=PATHS.map(p=>`<button class="path-option ${settings.pathId===p.id?'active':''}" data-path="${p.id}"><b>${p.label}</b><small>${p.hint}</small></button>`).join('');
  $('path-grid').querySelectorAll('[data-path]').forEach(b=>b.addEventListener('click',()=>{settings.pathId=b.dataset.path;drawPathMode=false;draftPath=[];syncDrawingUi();renderPaths();renderMotion();persistSettings();drawOrbit();updateReadout();}));
}
function renderMotion() {
  $('motion-controls').innerHTML=[
    control('orbitSpeedHz','Kecepatan orbit',.02,1.5,.01,' Hz',2),
    control('rotationSeconds','Durasi per putaran',.66,60,.1,' dtk',1),
    control('intervalMs','Interval ganti arah',100,10000,100,' ms',0,'Dipakai untuk mode bolak-balik atau acak'),
    control('smoothLevel','Penghalusan lintasan',0,8,1,'',0,'Level 0 mentah · 8 paling halus')
  ].join('')+(settings.pathId==='lissajous'?[control('lissajousFreqX','Frekuensi X',1,8,1,'',0),control('lissajousFreqZ','Frekuensi Z',1,8,1,'',0),control('lissajousPhase','Fase Lissajous',0,360,1,'°',0)].join(''):'')+`<div class="choice-block"><label>JENIS PERGERAKAN</label>${choice('movement',['Loop','Bolak-balik','Acak'])}</div><div class="choice-block"><label>ARAH PUTAR</label>${choice('clockwise',['Searah jarum jam','Berlawanan arah'])}</div>`;
  bindControls($('motion-controls'));
  bindChoices($('motion-controls'),(k,v)=>{settings[k]=k==='movement'?['loop','pingpong','random'][v]:v===0;renderMotion();persistSettings();});
  $('motion-controls').querySelector('#c-rotationSeconds')?.addEventListener('change',()=>{settings.orbitSpeedHz=1/Math.max(.66,settings.rotationSeconds);renderMotion();persistSettings();});
  $('motion-controls').querySelector('#c-orbitSpeedHz')?.addEventListener('change',()=>{settings.rotationSeconds=1/Math.max(.02,settings.orbitSpeedHz);renderMotion();persistSettings();});
}
function renderSpace() {
  $('space-controls').innerHTML=[
    control('width','Spatial width',0,2,.05,'×',2,'0 = mono · 2 = 200%'),
    control('room','Room distance',.2,3,.1,'×',1,'Jarak virtual dekat sampai jauh'),
    control('transitionSeconds','Transisi perpindahan',0,3,.1,' dtk',1),
    control('offsetX','Geser kiri ↔ kanan',-1,1,.05,'',2),
    control('offsetZ','Geser depan ↔ belakang',-1,1,.05,'',2)
  ].join(''); bindControls($('space-controls'));
}
function renderEq() {
  $('eq-presets').innerHTML=EQ_PRESETS.map(p=>`<button class="choice" data-eq-preset="${p.label}">${p.label}</button>`).join('');
  $('eq-presets').querySelectorAll('[data-eq-preset]').forEach(b=>b.addEventListener('click',()=>applyEqPreset(b.dataset.eqPreset)));
  const labels=['31','62','125','250','500','1k','2k','4k','8k','16k'];
  $('eq-sliders').innerHTML=settings.eq.map((v,i)=>`<div class="eq-band"><output id="eq-out-${i}">${v>0?'+':''}${num(v,0)}</output><input type="range" min="-15" max="15" step="0.5" value="${v}" data-eq-index="${i}" aria-label="EQ ${labels[i]} Hz"><small>${labels[i]}</small></div>`).join('');
  $('eq-sliders').querySelectorAll('[data-eq-index]').forEach(el=>el.addEventListener('input',()=>{const i=Number(el.dataset.eqIndex);settings.eq[i]=Number(el.value);$(`eq-out-${i}`).textContent=(settings.eq[i]>0?'+':'')+num(settings.eq[i],0);persistSettings();}));
}
function renderEnhance() {
  $('enhance-controls').innerHTML=[
    control('preampDb','Preamp gain',-12,12,.5,'dB',1),
    control('bassDb','Bass boost',0,15,.5,'dB',1),
    control('subBassPunch','Sub-bass punch',0,1,.01,'%',0),
    control('vocalExciter','Vocal clarity / exciter',0,1,.01,'%',0),
    control('channelBalance','Channel balance',-1,1,.01,'',2,'Negatif = kiri · positif = kanan'),
    control('volume','Volume utama DSP',0,1,.01,'%',0)
  ].join(''); bindControls($('enhance-controls'));
}
function renderFilters() {
  $('isolator-choices').innerHTML=choice('vocalIsolatorMode',['Mati','Karaoke','Acapella','Bass Only']);
  bindChoices($('isolator-choices'),(k,v)=>{settings[k]=v;renderFilters();persistSettings();});
  $('filter-controls').innerHTML=[
    control('highpassHz','High-pass filter',20,1000,5,' Hz',0),
    control('lowpassHz','Low-pass filter',300,20000,50,' Hz',0)
  ].join(''); bindControls($('filter-controls'));
}
function renderRooms() {
  $('room-presets').innerHTML=ROOM_PRESETS.map(r=>`<button class="room-choice ${settings.roomPresetId===r.id?'active':''}" data-room="${r.id}">${r.label}</button>`).join('');
  $('room-presets').querySelectorAll('[data-room]').forEach(b=>b.addEventListener('click',()=>{const r=ROOM_PRESETS.find(x=>x.id===b.dataset.room);settings.roomPresetId=r.id;settings.reverbDecay=r.decay;settings.reverbMix=r.mix;renderRooms();persistSettings();}));
  $('reverb-controls').innerHTML=[
    control('reverbMix','Dry / wet reverb',0,1,.01,'%',0),
    control('reverbDecay','Reverb decay',.3,8,.1,' dtk',1),
    control('echoMs','Echo delay',50,1000,10,' ms',0),
    control('echoFeedback','Echo feedback',0,.85,.01,'%',0),
    control('echoMix','Echo mix',0,1,.01,'%',0)
  ].join(''); bindControls($('reverb-controls'));
}
function renderCharacter() {
  $('character-controls').innerHTML=[
    control('tempo','Tempo / kecepatan',.5,2,.01,'×',2),
    control('pitchSemitones','Pitch',-12,12,.5,' semitone',1),
    control('drive','Drive / saturasi',0,1,.01,'%',0),
    control('compressor','Dynamic compressor',0,1,.01,'%',0)
  ].join(''); bindControls($('character-controls'));
  const limiter=$('softLimiter'); limiter.checked=Boolean(settings.softLimiter); limiter.onchange=()=>{setField('softLimiter',limiter.checked);};
  $('character-controls').insertAdjacentHTML('beforeend',`<label class="switch-row"><span><b>Keep pitch</b><small>Pertahankan pitch saat tempo berubah</small></span><input id="keepPitch" type="checkbox" ${settings.keepPitch?'checked':''}></label>`);
  $('keepPitch').onchange=()=>setField('keepPitch',$('keepPitch').checked);
}
function bindControls(root) {
  root.querySelectorAll('input[type=range][data-key]').forEach(el=>{
    const k=el.dataset.key;
    const renderValue=()=>{
      const v=Number(el.value); settings[k]=v;
      const out=root.querySelector(`#o-${k}`);
      if(out){let unit='';let digits=1;if(k==='preampDb'||k==='bassDb') {unit=' dB';digits=1;} else if(k==='orbitSpeedHz'){unit=' Hz';digits=2;} else if(k==='rotationSeconds'){unit=' dtk';} else if(k==='intervalMs'){unit=' ms';digits=0;} else if(k==='transitionSeconds'){unit=' dtk';} else if(k==='highpassHz'||k==='lowpassHz'){unit=' Hz';digits=0;} else if(k==='tempo'){unit='×';digits=2;} else if(k==='pitchSemitones'){unit=' semitone';} else if(['subBassPunch','vocalExciter','volume','drive','compressor','reverbMix','echoFeedback','echoMix'].includes(k)){unit='%';digits=0;}
        const shown=unit==='%'?v*100:v;
        out.textContent=unit===' dB'?`${v>0?'+':''}${num(v,digits)}${unit}`:`${num(shown,digits)}${unit}`;
      }
      persistSettings(); drawOrbit(); updateReadout();
    };
    el.addEventListener('input',renderValue);
  });
}
function bindChoices(root,cb) {
  root.querySelectorAll('[data-choice-key]').forEach(group=>group.querySelectorAll('[data-choice-value]').forEach(button=>button.addEventListener('click',()=>cb(group.dataset.choiceKey,Number(button.dataset.choiceValue)))));
}
function applyEqPreset(label) {
  const p=EQ_PRESETS.find(x=>x.label===label); if(!p)return;
  settings.eq=[...p.gains]; renderEq(); persistSettings(); toast(`EQ ${p.label} diterapkan`);
}
function renderPresetLists() {
  $('builtin-presets').innerHTML=BUILTIN_PRESETS.map(p=>`<div class="preset-item"><div class="preset-icon">✦</div><div class="preset-info"><b>${p.name}</b><small>${p.description}</small></div><button data-apply-builtin="${p.id}">TERAPKAN</button></div>`).join('');
  $('builtin-presets').querySelectorAll('[data-apply-builtin]').forEach(b=>b.addEventListener('click',()=>applyBuiltin(b.dataset.applyBuiltin)));
  $('eq-preset-cards').innerHTML=EQ_PRESETS.map(p=>`<button class="eq-preset-card" data-card-eq="${p.label}"><b>${p.label}</b><small>${p.gains.map(x=>x>0?'+'+x:x).join(' · ')}</small></button>`).join('');
  $('eq-preset-cards').querySelectorAll('[data-card-eq]').forEach(b=>b.addEventListener('click',()=>{applyEqPreset(b.dataset.cardEq);showTab('effects');}));
  renderSavedPresets();
}
async function applyBuiltin(id) {
  const p=BUILTIN_PRESETS.find(x=>x.id===id); if(!p)return;
  settings=makePreset(p); persistSettings(); renderAll();
  try { await SpatialBridge.applyPreset(id); } catch(err){console.warn(err);}
  toast(`Preset ${p.name} aktif`);
}
function renderSavedPresets() {
  const list=$('saved-presets');
  if(!savedPresets.length){list.innerHTML='<div class="empty-state">Belum ada preset tersimpan.</div>';return;}
  list.innerHTML=savedPresets.map(p=>`<div class="preset-item"><div class="preset-icon">♡</div><div class="preset-info"><b>${escapeHtml(p.name)}</b><small>Preset pribadi · disimpan di perangkat</small></div><button data-load-saved="${p.id}">MUAT</button><button class="delete-button" data-delete-saved="${p.id}">×</button></div>`).join('');
  list.querySelectorAll('[data-load-saved]').forEach(b=>b.addEventListener('click',()=>{const p=savedPresets.find(x=>x.id===b.dataset.loadSaved);if(p){settings={...DEFAULT_SETTINGS,...clone(p.settings)};persistSettings();renderAll();toast(`${p.name} dimuat`);}}));
  list.querySelectorAll('[data-delete-saved]').forEach(b=>b.addEventListener('click',async()=>{savedPresets=savedPresets.filter(x=>x.id!==b.dataset.deleteSaved);localStorage.setItem(CUSTOM_KEY,JSON.stringify(savedPresets));try{await SpatialBridge.deletePreset(b.dataset.deleteSaved);}catch{}renderSavedPresets();toast('Preset dihapus');}));
}
function escapeHtml(value){return String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function savePreset() {
  const name=$('preset-name').value.trim(); if(!name){toast('Isi nama preset terlebih dahulu');return;}
  const p={id:crypto.randomUUID?.()||String(Date.now()),name,settings:clone(settings)};
  savedPresets.unshift(p); localStorage.setItem(CUSTOM_KEY,JSON.stringify(savedPresets));
  SpatialBridge.savePreset(name,settings).catch(console.warn);
  $('preset-name').value='';renderSavedPresets();toast(`Preset ${name} tersimpan`);
}
function exportJson() {
  const payload={app:'Kruzen Spatial Audio',author:'DEV BAGJA / KruzenLab',name:$('preset-name').value.trim()||'Preset Kruzen',settings};
  const blob=new Blob([JSON.stringify(payload,null,2)],{type:'application/json'});downloadBlob(blob,'kruzen-spatial-preset.json');
  SpatialBridge.exportPreset(payload.name,settings).catch(()=>{});
}
function downloadBlob(blob,name){const a=document.createElement('a');const u=URL.createObjectURL(blob);a.href=u;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(u),1000);}
function importJsonText(text) {
  try {const obj=JSON.parse(text);const s=obj.settings||obj;if(!s||typeof s!=='object')throw new Error('Format tidak valid');settings={...DEFAULT_SETTINGS,...s,eq:Array.isArray(s.eq)&&s.eq.length===10?s.eq:DEFAULT_SETTINGS.eq};const name=obj.name||`Impor ${new Date().toLocaleDateString('id-ID')}`;savedPresets.unshift({id:crypto.randomUUID?.()||String(Date.now()),name,settings:clone(settings)});localStorage.setItem(CUSTOM_KEY,JSON.stringify(savedPresets));persistSettings();renderAll();SpatialBridge.importPreset(text).catch(()=>{});toast(`Preset ${name} diimpor`);}catch(e){toast(`Gagal impor JSON: ${e.message}`);}}
function showTab(tab) {
  document.querySelectorAll('.tab').forEach(b=>b.classList.toggle('active',b.dataset.tab===tab));
  document.querySelectorAll('.page').forEach(p=>p.classList.toggle('active',p.id===`page-${tab}`));
  window.scrollTo({top:0,behavior:'smooth'});
}
function renderAll(){renderPaths();renderMotion();renderSpace();renderEq();renderEnhance();renderFilters();renderRooms();renderCharacter();renderPresetLists();$('master-label').textContent=settings.enabled?'DSP AKTIF':'DSP NONAKTIF';$('master-toggle').classList.toggle('is-off',!settings.enabled);$('spatial-state').textContent=settings.enabled?'ENGINE ON':'ENGINE OFF';}
function drawOrbit() {
  const c=$('orbit-canvas'); if(!c)return; const ctx=c.getContext('2d');const w=c.width,h=c.height;ctx.clearRect(0,0,w,h);
  const cx=w/2,cy=h/2,rx=w*.34,rz=h*.33;
  ctx.strokeStyle='#263a43';ctx.lineWidth=1;
  for(let i=1;i<=3;i++){ctx.beginPath();ctx.ellipse(cx,cy,rx*i/3,rz*i/3,0,0,Math.PI*2);ctx.stroke();}
  ctx.beginPath();ctx.moveTo(cx-rx,cy);ctx.lineTo(cx+rx,cy);ctx.moveTo(cx,cy-rz);ctx.lineTo(cx,cy+rz);ctx.strokeStyle='#32464e';ctx.stroke();
  const pathId=settings.pathId;let pts=[];
  for(let i=0;i<160;i++){let t=i/160*Math.PI*2,x=0,z=0;
    if(pathId==='angka-8'){x=Math.sin(t);z=-.65*Math.sin(2*t)}
    else if(pathId==='pendulum'){let swing=Math.sin(t);x=swing;z=-.85+.55*(1-Math.cos(swing*Math.PI*.5));}
    else if(pathId==='spiral'){let r=.45+.5*(.5-.5*Math.cos(t));x=r*Math.sin(t*3);z=-r*Math.cos(t*3);}
    else if(pathId==='kiri-kanan'){x=-1+2*(i<80?i/80:(160-i)/80);z=0;}
    else if(pathId==='depan-belakang'){x=0;z=-1+2*(i<80?i/80:(160-i)/80);}
    else if(pathId==='diagonal'){let k=i<80?i/80:(160-i)/80;x=-.85+1.7*k;z=-.85+1.7*k;}
    else if(pathId==='elips'){x=Math.sin(t);z=-.5*Math.cos(t);}
    else if(pathId==='bintang'){let r=.6+.4*Math.cos(2.5*t);x=r*Math.sin(t);z=-r*Math.cos(t);}
    else if(pathId==='lissajous'){x=Math.sin((settings.lissajousFreqX||3)*t+settings.lissajousPhase*Math.PI/180);z=Math.sin((settings.lissajousFreqZ||2)*t);}
    else if(pathId==='custom' && Array.isArray(settings.customRaw) && settings.customRaw.length>1){const raw=settings.customRaw;const at=i/159*(raw.length-1);const j=Math.min(raw.length-2,Math.floor(at));const f=at-j;const a=raw[j],b=raw[j+1];x=Number(a?.[0]||0)*(1-f)+Number(b?.[0]||0)*f;z=Number(a?.[1]||0)*(1-f)+Number(b?.[1]||0)*f;}
    else{x=Math.sin(t);z=-Math.cos(t);}
    pts.push([cx+x*rx*.84,cy+z*rz*.84]);
  }
  ctx.beginPath();pts.forEach((p,i)=>i?ctx.lineTo(...p):ctx.moveTo(...p));ctx.closePath();ctx.strokeStyle='#47efae';ctx.lineWidth=3;ctx.shadowBlur=13;ctx.shadowColor='#47efae99';ctx.stroke();ctx.shadowBlur=0;
  if(draftPath.length>1){ctx.beginPath();draftPath.forEach((p,i)=>{const px=cx+p[0]*rx*.84,py=cy+p[1]*rz*.84;i?ctx.lineTo(px,py):ctx.moveTo(px,py);});ctx.strokeStyle='#f7d58a';ctx.lineWidth=2;ctx.setLineDash([5,5]);ctx.stroke();ctx.setLineDash([]);}
  const frame=lastFrame;const phase=frame?Math.max(0,Math.min(1,frame.progress||0)):((Date.now()/1000)/(Math.max(.66,settings.rotationSeconds||10))%1);const index=Math.floor(phase*pts.length)%pts.length;const current=pts[index];ctx.beginPath();ctx.arc(current[0],current[1],9,0,Math.PI*2);ctx.fillStyle='#d8fff0';ctx.shadowBlur=25;ctx.shadowColor='#47efae';ctx.fill();ctx.shadowBlur=0;
  if(frame){$('position-x').textContent=num(frame.x||0,2);$('position-z').textContent=num(frame.z||0,2);} else {$('position-x').textContent=num(settings.offsetX,2);$('position-z').textContent=num(settings.offsetZ,2);}
  $('position-path').textContent=settings.pathId==='custom'?'Jalur sendiri':(PATHS.find(p=>p.id===settings.pathId)?.label||'Custom');
}
function updateReadout(){drawOrbit();}
function syncDrawingUi(){const c=$('orbit-canvas');if(!c)return;c.classList.toggle('drawing',drawPathMode);$('draw-path').textContent=drawPathMode?'Selesai menggambar':'Gambar jalur sendiri';$('draw-hint').textContent=drawPathMode?'Tarik jari pada kanvas untuk membentuk lintasan.':'Pilih “Gambar jalur sendiri” lalu coret pada kanvas.';}
function pointerPoint(event){const c=$('orbit-canvas');const rect=c.getBoundingClientRect();const x=(event.clientX-rect.left)/rect.width*c.width;const y=(event.clientY-rect.top)/rect.height*c.height;return [Math.max(-1,Math.min(1,(x-c.width/2)/(c.width*.34*.84))),Math.max(-1,Math.min(1,(y-c.height/2)/(c.height*.33*.84)))];}
function drawSpectrum(spectrum=null){const c=$('spectrum-canvas');if(!c)return;const ctx=c.getContext('2d');const w=c.width,h=c.height;ctx.clearRect(0,0,w,h);ctx.strokeStyle='#1f3239';ctx.lineWidth=1;for(let y=1;y<4;y++){ctx.beginPath();ctx.moveTo(0,h*y/4);ctx.lineTo(w,h*y/4);ctx.stroke();}
  const vals=Array.isArray(spectrum)&&spectrum.length===24?spectrum:Array(24).fill(0);const gap=8,bw=(w-gap*25)/24;vals.forEach((v,i)=>{const level=Math.max(0,Math.min(1,Number(v)||0));const bar=Math.max(2,level*(h-20));const x=gap+i*(bw+gap),y=h-bar;const grad=ctx.createLinearGradient(0,y,0,h);grad.addColorStop(0,'#72f5c0');grad.addColorStop(1,'#178c72');ctx.fillStyle=grad;ctx.beginPath();ctx.roundRect(x,y,bw,bar,5);ctx.fill();});
}
function applyNativeState(state){if(!state)return;nativeState=state;if(state.settings){const incoming={...DEFAULT_SETTINGS,...state.settings};const changed=Object.keys(DEFAULT_SETTINGS).some(k=>JSON.stringify(incoming[k])!==JSON.stringify(settings[k]));if(changed){settings=incoming;renderAll();storeLocal();}}if(Array.isArray(state.savedPresets)){savedPresets=state.savedPresets.map(p=>({id:p.id,name:p.name,settings:p.settings||{...DEFAULT_SETTINGS}}));try{localStorage.setItem(CUSTOM_KEY,JSON.stringify(savedPresets));}catch{}renderSavedPresets();}if(state.frame){lastFrame=state.frame;drawOrbit();drawSpectrum(state.frame.spectrum);$('analyzer-note').textContent='Data analyzer diterima dari engine native.';}if(typeof state.universalCaptureActive==='boolean')$('capture-toggle').textContent=state.universalCaptureActive?'Matikan Capture':'Mulai Capture';if(typeof state.globalEqActive==='boolean')$('globalEq').checked=state.globalEqActive;if(typeof state.floatingBubbleEnabled==='boolean')$('floatingBubble').checked=state.floatingBubbleEnabled;if(typeof state.floatingLyricsEnabled==='boolean')$('floatingLyrics').checked=state.floatingLyricsEnabled;if(state.globalEqMessage)$('system-status').textContent=state.globalEqMessage;}
async function initNative(){const native=SpatialBridge.isNative();$('native-dot').classList.toggle('connected',native);$('native-label').textContent=native?'Android Native':'Web Preview';if(!native){$('analyzer-note').textContent='Preview UI: analyzer akan menerima data saat disambungkan ke engine native.';$('floating-notice').textContent='Preview web tidak memiliki izin overlay Android. Kontrol overlay dan audio sistem aktif setelah build Capacitor native.';$('capture-toggle').disabled=true;['floatingBubble','floatingLyrics','globalEq'].forEach(id=>$(id).disabled=true);return;}
  try{applyNativeState(await SpatialBridge.getState());await SpatialBridge.addFrameListener(frame=>{lastFrame=frame;drawSpectrum(frame.spectrum);drawOrbit();$('analyzer-note').textContent='Analyzer real-time · tersambung ke DSP native.';});await SpatialBridge.addStatusListener(applyNativeState);}catch(e){console.error(e);toast('Native bridge belum siap');}}

// Event wiring
$('master-toggle').addEventListener('click',()=>{settings.enabled=!settings.enabled;persistSettings();renderAll();toast(settings.enabled?'DSP diaktifkan':'DSP dimatikan');});
document.querySelectorAll('[data-tab]').forEach(b=>b.addEventListener('click',()=>showTab(b.dataset.tab)));
$('reset-spatial').addEventListener('click',()=>{for(const k of ['pathId','movement','clockwise','intervalMs','rotationSeconds','orbitSpeedHz','smoothLevel','lissajousFreqX','lissajousFreqZ','lissajousPhase','customRaw'])settings[k]=clone(DEFAULT_SETTINGS[k]);drawPathMode=false;draftPath=[];syncDrawingUi();renderPaths();renderMotion();persistSettings();});
$('draw-path').addEventListener('click',()=>{drawPathMode=!drawPathMode;draftPath=[];syncDrawingUi();drawOrbit();});
$('clear-path').addEventListener('click',()=>{settings.customRaw=[];if(settings.pathId==='custom')settings.pathId='lingkaran';drawPathMode=false;draftPath=[];syncDrawingUi();renderPaths();persistSettings();drawOrbit();toast('Jalur gambar dihapus');});
$('orbit-canvas').addEventListener('pointerdown',event=>{if(!drawPathMode)return;event.preventDefault();$('orbit-canvas').setPointerCapture?.(event.pointerId);draftPath=[pointerPoint(event)];drawOrbit();});
$('orbit-canvas').addEventListener('pointermove',event=>{if(!drawPathMode||!draftPath.length)return;const p=pointerPoint(event),last=draftPath[draftPath.length-1];if(Math.hypot(p[0]-last[0],p[1]-last[1])>.025){draftPath.push(p);drawOrbit();}});
const finishDrawing=()=>{if(!drawPathMode||draftPath.length<4)return;settings.customRaw=draftPath.map(p=>[Number(p[0].toFixed(4)),Number(p[1].toFixed(4))]);settings.pathId='custom';drawPathMode=false;draftPath=[];syncDrawingUi();renderPaths();persistSettings();drawOrbit();toast('Jalur 8D khusus diterapkan');};
$('orbit-canvas').addEventListener('pointerup',finishDrawing);$('orbit-canvas').addEventListener('pointercancel',finishDrawing);
$('eq-flat').addEventListener('click',()=>applyEqPreset('Datar'));
$('save-preset').addEventListener('click',savePreset);
$('export-json').addEventListener('click',exportJson);
$('import-json').addEventListener('click',()=>$('json-file').click());
$('json-file').addEventListener('change',async e=>{const file=e.target.files?.[0];if(file)importJsonText(await file.text());e.target.value='';});
$('floatingBubble').addEventListener('change',async()=>{try{const r=await SpatialBridge.setFloatingBubble($('floatingBubble').checked);if(r?.permissionRequired){$('floating-notice').textContent='Izin overlay dibuka. Setelah diizinkan, aktifkan kembali toggle ini.';toast('Berikan izin overlay di pengaturan Android');}else toast('Status gelembung diperbarui');}catch(e){$('floatingBubble').checked=false;toast(e.message||'Tidak dapat mengaktifkan bubble');}});
$('floatingLyrics').addEventListener('change',async()=>{try{const r=await SpatialBridge.setFloatingLyrics($('floatingLyrics').checked);if(r?.permissionRequired)toast('Berikan izin overlay di pengaturan Android');}catch(e){$('floatingLyrics').checked=false;toast('Tidak dapat mengaktifkan lirik melayang');}});
$('globalEq').addEventListener('change',async()=>{try{const r=await SpatialBridge.setGlobalEq($('globalEq').checked);$('system-status').textContent=r?.message||'Permintaan Global EQ dikirim.';}catch(e){$('globalEq').checked=false;$('system-status').textContent=e.message||'Global EQ tidak tersedia.';}});
$('capture-toggle').addEventListener('click',async()=>{try{if(nativeState?.universalCaptureActive){await SpatialBridge.stopCapture();$('capture-toggle').textContent='Mulai Capture';}else{await SpatialBridge.startCapture();$('capture-toggle').textContent='Meminta persetujuan…';}}catch(e){toast(e.message||'Capture tidak dapat dijalankan');}});
$('ir-file').addEventListener('click',async e=>{
  if(!SpatialBridge.isNative()) return;
  e.preventDefault();
  try {
    const picked=await SpatialBridge.chooseImpulseResponse();
    if(picked?.settings){settings={...DEFAULT_SETTINGS,...picked.settings};storeLocal();renderAll();$('ir-name').textContent=`${picked.name} · siap dipakai native convolver`;toast('Impulse Response tersambung ke engine native');}
  } catch(err){toast(err.message||'Gagal memilih file IR');}
});
$('ir-file').addEventListener('change',async e=>{
  const file=e.target.files?.[0]; if(!file)return;
  settings.customIrName=file.name; settings.customIrUri=null; persistSettings();
  $('ir-name').textContent=`${file.name} · preview web tidak menjalankan native convolver`;
  toast('Untuk pemrosesan WAV gunakan aplikasi Android.'); e.target.value='';
});


let orbitTimer = null;
let spatialReady = false;

export async function mountSpatial(container) {
  if (container) rootEl = container;
  if (spatialReady) {
    renderAll();
    return;
  }
  loadLocal();
  renderAll();
  syncDrawingUi();
  drawSpectrum();
  drawOrbit();
  initNative();
  if (orbitTimer) clearInterval(orbitTimer);
  orbitTimer = setInterval(()=>{ if (!lastFrame) drawOrbit(); }, 80);
  spatialReady = true;
}

export function unmountSpatial() {
  if (orbitTimer) { clearInterval(orbitTimer); orbitTimer = null; }
}

export function getSpatialSettings() {
  return clone(settings);
}

// Auto-boot if spatial-root already in document (standalone)
if (document.getElementById('spatial-root') || document.getElementById('master-toggle')) {
  loadLocal();renderAll();syncDrawingUi();drawSpectrum();drawOrbit();initNative();
  orbitTimer = setInterval(()=>{ if (!lastFrame) drawOrbit(); }, 80);
  spatialReady = true;
}
