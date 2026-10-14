/* Seekora landing page and API interactions */
const finePointer=matchMedia('(pointer:fine) and (min-width:768px)');
const reducedMotion=matchMedia('(prefers-reduced-motion:reduce)');
if(finePointer.matches)document.body.classList.add('fine');
let lp=0;const fill=document.getElementById('loaderFill');
const loader=setInterval(()=>{lp=Math.min(100,lp+20);fill.style.width=lp+'%';if(lp===100){clearInterval(loader);setTimeout(()=>{document.getElementById('preloader').classList.add('done');startTerminal();startTyped()},250)}},120);
function toggleTheme(){const root=document.documentElement;const dark=root.dataset.theme==='dark';root.dataset.theme=dark?'light':'dark';document.getElementById('themeBtn').textContent=dark?'☀️':'🌙';toast(dark?'Light mode activated':'Dark mode activated')}
const cur=document.getElementById('cursor'),ring=document.getElementById('cursorRing');let mx=innerWidth/2,my=innerHeight/2,rx=mx,ry=my;
if(finePointer.matches){
  addEventListener('mousemove',e=>{mx=e.clientX;my=e.clientY;cur.style.left=mx+'px';cur.style.top=my+'px'});
  (function followCursor(){rx+=(mx-rx)*.18;ry+=(my-ry)*.18;if(Math.abs(mx-rx)<.1)rx=mx;if(Math.abs(my-ry)<.1)ry=my;ring.style.left=rx+'px';ring.style.top=ry+'px';requestAnimationFrame(followCursor)})();
  const HOVER_TARGETS='a,button,.card,.sug,.faq-q,.palette-item,input,textarea';
  document.addEventListener('mouseover',e=>{if(e.target.closest&&e.target.closest(HOVER_TARGETS))document.body.classList.add('hovering')});
  document.addEventListener('mouseout',e=>{if(e.target.closest&&e.target.closest(HOVER_TARGETS)&&!(e.relatedTarget&&e.relatedTarget.closest&&e.relatedTarget.closest(HOVER_TARGETS)))document.body.classList.remove('hovering')});
}
addEventListener('scroll',()=>{const h=document.documentElement;const max=h.scrollHeight-h.clientHeight;document.getElementById('progressBar').style.width=(max?scrollY/max*100:0)+'%';document.getElementById('navbar').classList.toggle('scrolled',scrollY>40);document.getElementById('toTop').classList.toggle('show',scrollY>600)});
const canvas=document.getElementById('particles'),ctx=canvas.getContext('2d');let particles=[];
function resize(){canvas.width=innerWidth;canvas.height=innerHeight;particles=Array.from({length:Math.min(55,Math.max(22,Math.floor(innerWidth/25)))} ,()=>({x:Math.random()*canvas.width,y:Math.random()*canvas.height,vx:(Math.random()-.5)*.25,vy:(Math.random()-.5)*.25,r:Math.random()*1.6+.5}))}
function drawParticles(){ctx.clearRect(0,0,canvas.width,canvas.height);for(let i=0;i<particles.length;i++){const p=particles[i];if(!reducedMotion.matches){p.x+=p.vx;p.y+=p.vy;if(p.x<0||p.x>canvas.width)p.vx*=-1;if(p.y<0||p.y>canvas.height)p.vy*=-1}ctx.beginPath();ctx.arc(p.x,p.y,p.r,0,Math.PI*2);ctx.fillStyle='rgba(0,229,255,.65)';ctx.fill();for(let j=i+1;j<particles.length;j++){const q=particles[j],d=Math.hypot(p.x-q.x,p.y-q.y);if(d<105){ctx.beginPath();ctx.moveTo(p.x,p.y);ctx.lineTo(q.x,q.y);ctx.strokeStyle=`rgba(124,58,237,${.11*(1-d/105)})`;ctx.stroke()}}}if(!reducedMotion.matches)requestAnimationFrame(drawParticles)}
resize();drawParticles();addEventListener('resize',resize);
const phrases=['All Your Information Needs.','Smarter Research.','Faster Learning.','Clearer Answers.'];let pi=0,ci=0,del=false;
function startTyped(){const tick=()=>{const p=phrases[pi];document.getElementById('typed').textContent=p.slice(0,ci);if(!del&&ci<p.length){ci++;setTimeout(tick,55)}else if(!del){del=true;setTimeout(tick,1500)}else if(ci>0){ci--;setTimeout(tick,28)}else{del=false;pi=(pi+1)%phrases.length;setTimeout(tick,250)}};tick()}
function startTerminal(){const lines=[['p1','$ seekora ask "your question"'],['p3','> Sending request to the configured AI service…'],['p2','✓ Getting a clear response'],['p3','> Summarizing text when you need it…'],['p2','✓ Answer ready to review'],['p4','→ Ask a follow-up in the chat'],['p1','$ _']];const body=document.getElementById('termBody');let i=0;const next=()=>{if(i<lines.length){const d=document.createElement('div');d.className=lines[i][0];d.textContent=lines[i++][1];body.appendChild(d);setTimeout(next,500)}};next()}
const term=document.getElementById('terminal');addEventListener('mousemove',e=>{if(!term)return;const r=term.getBoundingClientRect(),dx=(e.clientX-r.left-r.width/2)/r.width,dy=(e.clientY-r.top-r.height/2)/r.height;if(Math.abs(dx)<1&&Math.abs(dy)<1)term.style.transform=`rotateY(${dx*6}deg) rotateX(${-dy*6}deg)`});
const items=['AI chat','Text summaries','Conversation history','Voice input','Spring Boot','REST API'];const marquee=items.map(t=>`<span>${t}</span>`).join('');document.getElementById('mq1').innerHTML=marquee;document.getElementById('mq2').innerHTML=marquee;
function apiBase(){return '/api/v1'}
async function chatRequest(message,storageKey){
  const send=id=>requestApi('/chat',{message,conversationId:id||null});
  let id=null;try{id=localStorage.getItem(storageKey)}catch{}
  let result;
  try{result=await send(id)}catch(e){if(id&&/not found/i.test(e.message)){result=await send(null)}else throw e}
  try{localStorage.setItem(storageKey,result.conversationId)}catch{}
  return result;
}
function answerNote(result){return result.notice||''}
async function requestApi(path,body){let response;try{response=await fetch(apiBase()+path,{method:body===undefined?'GET':'POST',headers:{Accept:'application/json',...(body===undefined?{}:{'Content-Type':'application/json'})},credentials:'same-origin',body:body===undefined?undefined:JSON.stringify(body)})}catch{throw new Error('Could not connect to Seekora. Check that the server is running.')};if(response.status===204){if(!response.ok)throw new Error(`Request failed (${response.status}).`);return null}let data;try{data=await response.json()}catch{throw new Error(`Seekora returned an invalid response (${response.status}).`)}if(!response.ok)throw new Error(data.detail||data.message||`Request failed (${response.status}).`);return data}
function renderInline(parent,text){
  const re=/(`[^`\n]+`|\*\*[^*\n]+\*\*|\[[^\]\n]+\]\(https?:\/\/[^)\s]+\))/g;let last=0,m;
  while((m=re.exec(text))){
    if(m.index>last)parent.appendChild(document.createTextNode(text.slice(last,m.index)));
    const t=m[0];
    if(t[0]==='`'){const c=document.createElement('code');c.textContent=t.slice(1,-1);parent.appendChild(c)}
    else if(t[0]==='*'){const b=document.createElement('strong');b.textContent=t.slice(2,-2);parent.appendChild(b)}
    else{const i=t.indexOf('](');const a=document.createElement('a');a.textContent=t.slice(1,i);a.href=t.slice(i+2,-1);a.target='_blank';a.rel='noopener noreferrer';parent.appendChild(a)}
    last=m.index+t.length;
  }
  if(last<text.length)parent.appendChild(document.createTextNode(text.slice(last)));
}
function renderMarkdown(text){
  const root=document.createElement('div');root.className='md';
  const lines=String(text).replace(/\r\n?/g,'\n').split('\n');let i=0,list=null;
  const closeList=()=>{list=null};
  while(i<lines.length){
    const line=lines[i];
    const fence=line.match(/^```\s*([\w+#.-]*)\s*$/);
    if(fence){closeList();const code=[];i++;while(i<lines.length&&!/^```\s*$/.test(lines[i]))code.push(lines[i++]);i++;const pre=document.createElement('pre'),c=document.createElement('code');if(fence[1])pre.dataset.lang=fence[1];c.textContent=code.join('\n');pre.appendChild(c);root.appendChild(pre);continue}
    const bullet=line.match(/^\s*[-*•]\s+(.*)$/),numbered=line.match(/^\s*\d+[.)]\s+(.*)$/);
    if(bullet||numbered){const tag=bullet?'UL':'OL';if(!list||list.tagName!==tag){list=document.createElement(tag.toLowerCase());root.appendChild(list)}const li=document.createElement('li');renderInline(li,(bullet||numbered)[1]);list.appendChild(li);i++;continue}
    closeList();
    const heading=line.match(/^#{1,6}\s+(.*)$/);
    if(heading){const h=document.createElement('h4');renderInline(h,heading[1]);root.appendChild(h);i++;continue}
    if(!line.trim()){i++;continue}
    const para=[line];i++;while(i<lines.length&&lines[i].trim()&&!/^```/.test(lines[i])&&!/^\s*([-*•]|\d+[.)])\s+/.test(lines[i])&&!/^#{1,6}\s/.test(lines[i]))para.push(lines[i++]);
    const p=document.createElement('p');renderInline(p,para.join(' '));root.appendChild(p);
  }
  return root;
}
function showAnswer(text,label='Seekora Summary',sources=[],note=''){const box=document.getElementById('searchResult');document.querySelector('#searchResult h3').textContent='💡 '+label;const resultText=document.getElementById('resultText');resultText.replaceChildren(renderMarkdown(text));if(note){const n=document.createElement('p');n.className='answer-note';n.textContent=note;resultText.appendChild(n)}const links=document.getElementById('sourceLinks');links.replaceChildren();if(!sources.length){links.textContent='No web sources for this summary.'}else{const heading=document.createElement('strong');heading.textContent='Sources';links.appendChild(heading);const list=document.createElement('ol');for(const source of sources){try{const url=new URL(source.url);if(!['http:','https:'].includes(url.protocol))continue;const item=document.createElement('li'),anchor=document.createElement('a');anchor.href=url.href;anchor.target='_blank';anchor.rel='noopener noreferrer';anchor.textContent=source.title||url.hostname;item.appendChild(anchor);list.appendChild(item)}catch{}}links.appendChild(list)}box.style.display='block';box.scrollIntoView({behavior:'smooth',block:'nearest'})}
function setBusy(button,busy,label){if(!button)return;if(busy){button.dataset.label=button.textContent;button.textContent=label;button.disabled=true}else{button.textContent=button.dataset.label||button.textContent;button.disabled=false}}
function demoConversationKey(){return 'seekora-demo-conversation:'+(signedInUser?signedInUser.id:'guest')}
async function demoSearch(){const q=document.getElementById('searchInput').value.trim();if(!q)return toast('Please enter a question first.');const button=document.querySelector('#demo .search-box .btn.magnetic');setBusy(button,true,'Searching…');try{const result=await chatRequest(q,'seekora-demo-conversation');showAnswer(result.assistantMessage.content,result.answerMode==='ai'?'AI answer':'Web extract (no AI model configured)',result.sources||[],answerNote(result))}catch(e){showAnswer(e.message,'Search failed')}finally{setBusy(button,false)}}
async function summarizeText(){const text=document.getElementById('summaryInput').value.trim();if(!text)return toast('Paste some text to summarize first.');const button=document.querySelector('#demo textarea + button');setBusy(button,true,'Summarizing…');try{const result=await requestApi('/summarize',{text});showAnswer(result.summary,'Text summary')}catch(e){showAnswer(e.message,'Could not summarize')}finally{setBusy(button,false)}}
function askSug(q){document.getElementById('searchInput').value=q;demoSearch()}
function voiceSearch(){const SR=window.SpeechRecognition||window.webkitSpeechRecognition;if(!SR)return toast('Voice search is not supported in this browser.');const rec=new SR();rec.lang='en-US';const b=document.getElementById('micBtn');b.classList.add('listening');toast('Listening… speak now');rec.onresult=e=>{document.getElementById('searchInput').value=e.results[0][0].transcript;demoSearch()};rec.onend=()=>b.classList.remove('listening');rec.onerror=()=>{b.classList.remove('listening');toast('Could not hear you. Try again.')};rec.start()}
const cObs=new IntersectionObserver(es=>es.forEach(e=>{if(!e.isIntersecting)return;cObs.unobserve(e.target);const target=+e.target.dataset.target;let n=0;(function tick(){n=Math.min(target,n+Math.max(1,target/60));e.target.textContent=Math.floor(n);if(n<target)requestAnimationFrame(tick)})()}),{threshold:.5});document.querySelectorAll('.counter').forEach(c=>cObs.observe(c));
document.querySelectorAll('.card').forEach(card=>{card.addEventListener('mousemove',e=>{const r=card.getBoundingClientRect();card.style.setProperty('--mx',(e.clientX-r.left)+'px');card.style.setProperty('--my',(e.clientY-r.top)+'px');card.style.transform=`perspective(800px) rotateY(${(e.clientX-r.left-r.width/2)/r.width*5}deg) rotateX(${-(e.clientY-r.top-r.height/2)/r.height*5}deg) translateY(-6px)`});card.addEventListener('mouseleave',()=>card.style.transform='')});
document.querySelectorAll('.magnetic').forEach(btn=>{btn.addEventListener('mousemove',e=>{const r=btn.getBoundingClientRect();btn.style.transform=`translate(${(e.clientX-r.left-r.width/2)*.12}px,${(e.clientY-r.top-r.height/2)*.18}px)`});btn.addEventListener('mouseleave',()=>btn.style.transform='')});
const rObs=new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting){e.target.classList.add('visible');rObs.unobserve(e.target)}}),{threshold:.1});document.querySelectorAll('.reveal').forEach(el=>rObs.observe(el));
function toggleFaq(btn){const item=btn.parentElement,a=item.querySelector('.faq-a'),open=item.classList.contains('open');document.querySelectorAll('.faq-item').forEach(f=>{f.classList.remove('open');f.querySelector('.faq-a').style.maxHeight=null});if(!open){item.classList.add('open');a.style.maxHeight=a.scrollHeight+'px'}}
let toastT;function toast(msg){const t=document.getElementById('toast');t.textContent=msg;t.classList.add('show');clearTimeout(toastT);toastT=setTimeout(()=>t.classList.remove('show'),3200)}
function subscribe(){toast('Newsletter signup is not connected yet.')}
function appUrl(){return '#demo'}
let authMode='signup',signedInUser=null,aiStatus=null;
function setAccountState(user){signedInUser=user;const button=document.getElementById('accountButton'),greeting=document.getElementById('accountGreeting');if(user){greeting.textContent=`Hi, ${user.name}`;greeting.hidden=false;button.textContent='Log Out';button.onclick=logoutAccount}else{greeting.textContent='';greeting.hidden=true;button.textContent='Get Started';button.onclick=()=>openAuth('signup')}}
async function refreshAccount(){try{const status=await requestApi('/status');setAccountState(status.account||null);aiStatus=status}catch{setAccountState(null)}}
function openAuth(mode='signup'){authTab(mode);document.getElementById('authModal').classList.add('open');document.getElementById(mode==='signup'?'authName':'authEmail').focus()}
function closeAuth(){document.getElementById('authModal').classList.remove('open')}
function authTab(mode){authMode=mode;const signup=mode==='signup',name=document.getElementById('authName'),pass=document.getElementById('authPass');document.getElementById('tabSignup').classList.toggle('on',signup);document.getElementById('tabLogin').classList.toggle('on',!signup);name.style.display=signup?'':'none';name.required=signup;pass.autocomplete=signup?'new-password':'current-password';pass.minLength=signup?12:0;pass.placeholder=signup?'Password (at least 12 characters)':'Password';document.getElementById('authSubmit').textContent=signup?'Create Account':'Log In';document.getElementById('authError').textContent=''}
async function doAuth(){const name=document.getElementById('authName').value.trim(),email=document.getElementById('authEmail').value.trim(),password=document.getElementById('authPass').value,button=document.getElementById('authSubmit'),error=document.getElementById('authError');if(authMode==='signup'&&name.length<2){error.textContent='Please enter your name.';return}if(!email||!password){error.textContent='Enter your email and password.';return}if(authMode==='signup'&&password.length<12){error.textContent='Use a password with at least 12 characters.';return}button.disabled=true;button.textContent=authMode==='signup'?'Creating account…':'Signing in…';error.textContent='';try{const user=await requestApi(authMode==='signup'?'/auth/register':'/auth/login',authMode==='signup'?{name,email,password}:{email,password});localStorage.removeItem('seekora-demo-conversation');localStorage.removeItem('seekora-widget-conversation');setAccountState(user);closeAuth();document.getElementById('authPass').value='';toast(`Welcome, ${user.name}!`)}catch(e){error.textContent=e.message}finally{button.disabled=false;button.textContent=authMode==='signup'?'Create Account':'Log In'}}
async function logoutAccount(){try{await requestApi('/auth/logout',{});setAccountState(null);localStorage.removeItem('seekora-demo-conversation');localStorage.removeItem('seekora-widget-conversation');document.getElementById('chatBody').replaceChildren();addChatMessage('Hi! I am Seekora. Ask a question or request help summarizing text.','bot');document.getElementById('searchResult').style.display='none';toast('You have signed out.')}catch(e){toast(e.message)}}
document.querySelectorAll('#authName,#authEmail,#authPass').forEach(input=>input.addEventListener('keydown',event=>{if(event.key==='Enter')doAuth()}));
refreshAccount();
let billingCycle='MONTHLY';function setBilling(m){billingCycle=m==='y'?'YEARLY':'MONTHLY';document.getElementById('mBtn').classList.toggle('on',m==='m');document.getElementById('yBtn').classList.toggle('on',m==='y');toast('Plans and payments are not connected yet.')}function choosePlan(){toast('Plans and payments are not connected yet.')}
function toggleChat(){document.getElementById('chatPanel').classList.toggle('open');if(document.getElementById('chatPanel').classList.contains('open'))document.getElementById('chatInput').focus()}
function addChatMessage(text,kind,sources=[],note=''){const message=document.createElement('div');message.className=`msg ${kind}`;const content=document.createElement('div');if(kind==='bot')content.appendChild(renderMarkdown(text));else content.textContent=text;message.appendChild(content);if(note){const n=document.createElement('div');n.className='answer-note';n.textContent=note;message.appendChild(n)}if(sources.length){const links=document.createElement('div');links.className='chat-sources';for(const source of sources){try{const url=new URL(source.url);if(!['http:','https:'].includes(url.protocol))continue;const anchor=document.createElement('a');anchor.href=url.href;anchor.target='_blank';anchor.rel='noopener noreferrer';anchor.textContent=source.title||url.hostname;links.appendChild(anchor)}catch{}}message.appendChild(links)}const body=document.getElementById('chatBody');body.appendChild(message);body.scrollTop=body.scrollHeight}
async function sendChat(){const inp=document.getElementById('chatInput'),q=inp.value.trim();if(!q)return;inp.value='';addChatMessage(q,'user');const body=document.getElementById('chatBody'),t=document.createElement('div');t.className='msg bot typing';t.innerHTML='<i></i><i></i><i></i>';body.appendChild(t);body.scrollTop=body.scrollHeight;try{const result=await chatRequest(q,'seekora-widget-conversation');t.remove();addChatMessage(result.assistantMessage.content,'bot',result.sources||[],answerNote(result))}catch(e){t.remove();addChatMessage(e.message,'bot')}}
const commands=[{icon:'🏠',label:'Go to Home',act:()=>location.href='#hero'},{icon:'✨',label:'View Features',act:()=>location.href='#features'},{icon:'⚙️',label:'How It Works',act:()=>location.href='#how'},{icon:'💬',label:'Open AI Chat',act:()=>toggleChat()},{icon:'🌙',label:'Toggle Theme',act:()=>toggleTheme()},{icon:'🔍',label:'Try the Demo',act:()=>{location.href='#demo';setTimeout(()=>document.getElementById('searchInput').focus(),500)}}];let palIdx=0;
function renderPalette(list){document.getElementById('paletteList').innerHTML=list.map((c,i)=>`<div class="palette-item ${i===palIdx?'active':''}" data-act="runCmd" data-arg="${commands.indexOf(c)}">${c.icon} ${c.label}</div>`).join('')}
function openPalette(){document.getElementById('palette').classList.add('open');palIdx=0;renderPalette(commands);setTimeout(()=>document.getElementById('paletteInput').focus(),50)}function closePalette(){document.getElementById('palette').classList.remove('open');document.getElementById('paletteInput').value=''}function runCmd(i){commands[i]?.act();closePalette()}function filterPalette(){const q=document.getElementById('paletteInput').value.toLowerCase();palIdx=0;renderPalette(commands.filter(c=>c.label.toLowerCase().includes(q)))}function paletteKeys(e){const vis=[...document.querySelectorAll('.palette-item')];if(e.key==='ArrowDown'){e.preventDefault();palIdx=Math.min(palIdx+1,vis.length-1)}else if(e.key==='ArrowUp'){e.preventDefault();palIdx=Math.max(palIdx-1,0)}else if(e.key==='Enter'&&vis[palIdx]){vis[palIdx].click();return}else return;vis.forEach((v,i)=>v.classList.toggle('active',i===palIdx))}
addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();openPalette()}if(e.key==='Escape'){closePalette();closeAuth()}});

/* Event wiring (the page ships a strict CSP, so there are no inline on*= handlers). */
const actions={
  toggleTheme,toggleChat,sendChat,demoSearch,voiceSearch,summarizeText,subscribe,closeAuth,doAuth,
  openAuth:a=>openAuth(a||'signup'),authTab:a=>authTab(a),askSug:a=>askSug(a),runCmd:a=>runCmd(+a),
  goto:a=>{location.href=a},
  toggleNav:()=>document.getElementById('navLinks').classList.toggle('open'),
  toTop:()=>scrollTo({top:0,behavior:reducedMotion.matches?'auto':'smooth'}),
  toggleFaq:(a,el)=>toggleFaq(el),
};
document.addEventListener('click',e=>{
  const el=e.target.closest('[data-act]');if(!el)return;
  const name=el.dataset.act;
  if(name==='backdropCloseAuth'){if(e.target===el)closeAuth();return}
  if(name==='backdropClosePalette'){if(e.target===el)closePalette();return}
  const fn=actions[name];if(fn)fn(el.dataset.arg,el);
});
document.addEventListener('keydown',e=>{
  const el=e.target.closest&&e.target.closest('[data-enter],[data-keys]');if(!el)return;
  if(el.dataset.enter&&e.key==='Enter'){e.preventDefault();actions[el.dataset.enter]()}
  if(el.dataset.keys==='paletteKeys')paletteKeys(e);
});
document.addEventListener('input',e=>{if(e.target.dataset&&e.target.dataset.input==='filterPalette')filterPalette()});
