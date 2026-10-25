package com.galleryslim.share

/**
 * The page the laptop opens. Self-contained (inline CSS/JS, no external
 * resources — works with no internet). Flow: show QR → poll /api/status →
 * when approved, save files. In Chrome/Edge the user picks a folder once
 * (File System Access API) and files stream straight into it, in parallel;
 * other browsers get normal downloads.
 */
object SharePage {
    /** [downloads]: the receiver archives this build includes (see [ReceiverDownloads]); empty → "not included". */
    fun html(sessionId: String, secret: String, qrSvg: String, phoneName: String, downloads: List<String> = emptyList()): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>GallerySlim · Send to this computer</title>
<style>
:root{--bg:#f7f7f4;--card:#fff;--ink:#1a1c1b;--muted:#54565a;--teal:#0b6b5f;--green:#157a33;--line:rgba(26,28,27,.08)}
@media(prefers-color-scheme:dark){:root{--bg:#121413;--card:#1c1e1d;--ink:#eceeec;--muted:#b4b8b4;--teal:#53d6c0;--green:#5bd57f;--line:rgba(255,255,255,.1)}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:24px}
.card{background:var(--card);border:1px solid var(--line);border-radius:24px;padding:32px;max-width:560px;width:100%;text-align:center}
h1{font-size:22px;margin:0 0 4px}p{color:var(--muted);margin:6px 0}
.qr{display:inline-block;background:#fff;padding:16px;border-radius:16px;margin:18px 0 6px;box-shadow:0 8px 30px rgba(0,0,0,.12)}
.qr svg{display:block;width:280px;height:280px}
.steps{text-align:left;margin:18px auto 0;max-width:380px;color:var(--muted)}.steps li{margin:6px 0}
.hidden{display:none}.btn{display:inline-block;border:0;border-radius:999px;padding:14px 26px;font-size:16px;font-weight:600;cursor:pointer;background:var(--teal);color:#fff;margin-top:14px}.btn.alt{background:transparent;color:var(--teal);border:1px solid var(--teal)}a{color:var(--teal)}
.bar{height:10px;border-radius:5px;background:var(--line);overflow:hidden;margin:16px 0 6px}.bar i{display:block;height:100%;width:0;background:var(--green);transition:width .3s}
.list{text-align:left;max-height:220px;overflow:auto;margin-top:12px;font-size:14px}.list div{display:flex;justify-content:space-between;padding:4px 0;border-bottom:1px solid var(--line)}
.ok{color:var(--green);font-weight:600}.dim{color:var(--muted);font-size:13px}
h2{font-size:17px;margin:26px 0 2px;padding-top:18px;border-top:1px solid var(--line)}
.get .btn{display:block;text-decoration:none;margin:14px auto 6px;max-width:440px}.get .btn.alt{font-size:15px;padding:12px 20px}
.others{font-size:14px;margin:6px 0}.others a{margin:0 6px;white-space:nowrap}
.how{list-style:none;padding:0;margin:14px 0 4px;display:flex;flex-wrap:wrap;justify-content:center;gap:6px 18px;color:var(--ink);font-size:15px}
.how li b{display:inline-block;width:22px;height:22px;line-height:22px;border-radius:50%;background:var(--teal);color:#fff;font-size:12px;margin-right:6px}
.note{font-size:13px;color:var(--muted);text-align:left;background:var(--bg);border-radius:12px;padding:10px 14px;margin-top:12px}
</style></head><body><div class="card">
<div id="wait">
  <h1>Send to this computer</h1>
  <p>Easiest: the free GallerySlim Receiver app.</p>
  <div class="get" id="get">
    <div id="getbtns"></div>
    <p class="others" id="others"></p>
    <ol class="how"><li><b>1</b>Open it</li><li><b>2</b>Scan its code with your phone</li><li><b>3</b>Photos land in a folder you choose</li></ol>
    <p class="note" id="getnote">The first time, your computer may warn that the app is from an unknown developer (it isn't signed yet).
      <span id="n-win">Windows: click <b>More info</b> → <b>Run anyway</b>.</span>
      <span id="n-mac">Mac: Control-click the app → <b>Open</b> → <b>Open</b>.</span>
      <span id="n-linux">Linux: extract it, then run <b>./GallerySlim-Receiver</b>.</span>
      <a href="/receiver/SHA256SUMS" id="sums">Checksums</a></p>
  </div>
  <p class="dim hidden" id="noget">Download not included in this build.</p>
  <h2>Or download in this browser</h2>
  <p>From <b>$phoneName</b> · only this screen can receive</p>
  <div class="qr">$qrSvg</div>
  <ol class="steps"><li>On the phone, tap <b>Scan the laptop's code</b>.</li><li>Point the camera at this square.</li><li>Confirm with your fingerprint or PIN.</li></ol>
  <p class="dim" id="hint">Waiting for the phone…</p>
</div>
<div id="ready" class="hidden">
  <h1 id="rtitle">Ready to save</h1>
  <p id="rsub"></p>
  <button class="btn" id="zip">Save all as one organized file</button>
  <p class="dim" id="ziphint">One ZIP in your Downloads folder. Unzip it: photos are sorted into <b>GallerySlim / year-month</b> folders.</p>
  <button class="btn alt hidden" id="pick">Save into a folder I choose</button>
  <p class="dim hidden" id="pickhint">Sorted into year-month folders inside the folder you pick.</p>
  <p class="dim"><a href="#" id="one">Download files one by one instead</a></p>
  <div class="bar"><i id="fill"></i></div>
  <p id="prog" class="dim"></p>
  <div class="list" id="list"></div>
</div>
<div id="gone" class="hidden"><h1>This link has expired</h1><p>Reload the page on the laptop and scan the new code.</p></div>
</div>
<script>
const sid=${jsStr(sessionId)},secret=${jsStr(secret)};let files=[];
const q=s=>document.getElementById(s);const fmt=b=>b>=1<<30?(b/(1<<30)).toFixed(1)+' GB':b>=1<<20?(b/(1<<20)).toFixed(0)+' MB':(b/1024).toFixed(0)+' KB';
async function poll(){try{const r=await fetch('/api/status?s='+sid,{cache:'no-store'});const j=await r.json();
 if(j.state==='expired'){q('wait').classList.add('hidden');q('gone').classList.remove('hidden');return}
 if(j.state==='approved'){files=j.files;show();return}}catch(e){}
 setTimeout(poll,1500)}
function show(){q('wait').classList.add('hidden');q('ready').classList.remove('hidden');
 const total=files.reduce((a,f)=>a+f.size,0);q('rsub').textContent=files.length+' files · '+fmt(total);
 q('list').innerHTML=files.map(f=>'<div><span>'+esc(f.name)+'</span><span class="dim" id="st-'+f.id+'">'+fmt(f.size)+'</span></div>').join('')}
function esc(s){return s.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}
function url(f){return '/api/file?s='+sid+'&t='+encodeURIComponent(secret)+'&f='+f.id}
// Folder picking only exists on secure pages (Chrome/Edge over HTTPS); over plain Wi-Fi HTTP it is absent,
// so the organized ZIP is the universal path and the picker is offered only when available.
if(window.showDirectoryPicker&&window.isSecureContext){q('pick').classList.remove('hidden');q('pickhint').classList.remove('hidden')}
q('zip').onclick=()=>{q('zip').disabled=true;q('zip').textContent='Preparing your file…';
 const a=document.createElement('a');a.href='/api/zip?s='+sid+'&t='+encodeURIComponent(secret);a.click();
 files.forEach(f=>{q('st-'+f.id).textContent='in the ZIP';q('st-'+f.id).className='ok'});
 setTimeout(()=>{q('zip').textContent='Saved as one file ✓';q('rtitle').textContent='Check your Downloads folder'},1500)};
q('one').onclick=async e=>{e.preventDefault();await saveAll(null)};
q('pick').onclick=async()=>{q('pick').disabled=true;let dir=null;
 try{dir=await window.showDirectoryPicker({mode:'readwrite'})}catch(e){q('pick').disabled=false;return}
 await saveAll(dir)};
async function saveAll(dir){const total=files.reduce((a,f)=>a+f.size,0);let done=0;const per={};
 const update=()=>{const d=Object.values(per).reduce((a,b)=>a+b,0);q('fill').style.width=(100*d/total)+'%';q('prog').textContent=fmt(d)+' of '+fmt(total)};
 const save=async f=>{const r=await fetch(url(f));if(!r.ok)throw new Error(r.status);
  if(dir){const sub=await (await dir.getDirectoryHandle('GallerySlim',{create:true})).getDirectoryHandle(f.folder||'undated',{create:true});
   let name=f.name,n=2;while(await sub.getFileHandle(name,{create:false}).then(()=>true,()=>false)){const i=f.name.lastIndexOf('.');name=(i>0?f.name.slice(0,i):f.name)+' ('+(n++)+')'+(i>0?f.name.slice(i):'')}
   const h=await sub.getFileHandle(name,{create:true});const w=await h.createWritable();const rd=r.body.getReader();per[f.id]=0;
   while(true){const{done:dn,value}=await rd.read();if(dn)break;await w.write(value);per[f.id]+=value.length;update()}await w.close()}
  else{const b=await r.blob();per[f.id]=f.size;update();const a=document.createElement('a');a.href=URL.createObjectURL(b);a.download=f.name;a.click();setTimeout(()=>URL.revokeObjectURL(a.href),5000)}
  q('st-'+f.id).textContent='✓ saved';q('st-'+f.id).className='ok';done++};
 const queue=files.slice();const workers=Array.from({length:dir?4:2},async()=>{while(queue.length){const f=queue.shift();try{await save(f)}catch(e){q('st-'+f.id).textContent='failed'}}});
 await Promise.all(workers);q('rtitle').textContent=done===files.length?'All saved ✓':done+' of '+files.length+' saved';q('ziphint').textContent=dir?'Saved into the folder you chose, sorted by month.':'Saved to your Downloads folder.'}
// GallerySlim Receiver download: pick this computer's system from the browser (no network lookups).
const dl=${jsArr(downloads)};
(function(){const W='GallerySlim-Receiver-windows-amd64.zip',MA='GallerySlim-Receiver-macos-arm64.zip',MI='GallerySlim-Receiver-macos-amd64.zip',L='GallerySlim-Receiver-linux-amd64.tar.gz';
 const all=[[W,'Windows'],[MA,'Mac (Apple chip)'],[MI,'Mac (Intel)'],[L,'Linux']].filter(x=>dl.includes(x[0]));
 if(!all.length){q('get').classList.add('hidden');q('noget').classList.remove('hidden');return}
 if(!dl.includes('SHA256SUMS'))q('sums').classList.add('hidden');
 const ua=navigator.userAgent||'',uad=navigator.userAgentData,plat=(uad&&uad.platform)||navigator.platform||'';
 let os='';if(/Win/i.test(plat)||/Windows/.test(ua))os='win';else if(/Android|CrOS|iPhone|iPad/.test(ua))os='';
 else if(/Mac/i.test(plat)||/Macintosh|Mac OS X/.test(ua))os='mac';else if(/Linux/i.test(plat)||/Linux|X11/.test(ua))os='linux';
 let arch='';if(os==='mac'){try{const c=document.createElement('canvas').getContext('webgl'),x=c&&c.getExtension('WEBGL_debug_renderer_info'),r=x?String(c.getParameter(x.UNMASKED_RENDERER_WEBGL)):'';
  if(/Apple M\d|Apple M[0-9]/.test(r))arch='arm';else if(/Intel|AMD|Radeon|NVIDIA/i.test(r))arch='x64'}catch(e){}}
 const link=(f,t,cls)=>'<a class="'+cls+'" href="/receiver/'+f+'" download>'+t+'</a>';
 let main=[];
 if(os==='win')main=[[W,'Download GallerySlim Receiver for Windows']];
 else if(os==='linux')main=[[L,'Download GallerySlim Receiver for Linux']];
 else if(os==='mac')main=arch==='arm'?[[MA,'Download GallerySlim Receiver for Mac']]:arch==='x64'?[[MI,'Download GallerySlim Receiver for Mac']]:
  [[MA,'Download for Mac with Apple chip (M1 or newer)'],[MI,'Download for Mac with Intel chip']];
 main=main.filter(x=>dl.includes(x[0]));
 q('getbtns').innerHTML=main.map((x,i)=>link(x[0],x[1],i?'btn alt':'btn')).join('');
 const rest=all.filter(x=>!main.some(m=>m[0]===x[0]));
 q('others').innerHTML=(main.length?'Also for: ':'Download GallerySlim Receiver for: ')+rest.map(x=>link(x[0],x[1],'')).join(' · ');
 ['win','mac','linux'].forEach(o=>{if(os&&o!==os)q('n-'+o).classList.add('hidden')});
})();
poll();
</script></body></html>"""

    /** Only names from [ReceiverDownloads.FILES] reach the page (constants, no user input). */
    private fun jsArr(names: List<String>) =
        names.filter { it in ReceiverDownloads.FILES }.joinToString(",", "[", "]") { jsStr(it) }

    private fun jsStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
