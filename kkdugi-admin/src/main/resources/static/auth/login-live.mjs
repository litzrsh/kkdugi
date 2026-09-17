import {saveToken,clearToken} from './session.mjs';
const form=document.getElementById('login-form');
const username=document.getElementById('username');
const password=document.getElementById('password');
const submit=document.getElementById('login-submit');
const toggle=document.querySelector('.password-toggle');
const panel=document.getElementById('duplicate-panel');
const force=document.getElementById('force-login');
const cancel=document.getElementById('cancel-login');
const error=document.getElementById('login-error');
const errorText=error.querySelector('span');
const language=document.getElementById('login-language');
let busy=false;
const normalLabel=submit.querySelector('span').textContent;
function duplicate(show){form.hidden=show;panel.hidden=!show;document.getElementById('login-intro').hidden=show;(show?document.getElementById('duplicate-title'):username).focus();}
function setBusy(value){busy=value;[submit,force,cancel,toggle,username,password,language].forEach(el=>el.disabled=value);form.setAttribute('aria-busy',String(value));panel.setAttribute('aria-busy',String(value));submit.querySelector('span').textContent=value?submit.dataset.busyLabel:normalLabel;}
async function signIn(forceLogin=false){
 if(busy)return;
 error.hidden=true;setBusy(true);
 try{
  const response=await fetch(form.action,{method:'POST',headers:{'Accept':'application/json','Content-Type':'application/json'},body:JSON.stringify({username:username.value,password:password.value,force:forceLogin})});
  const body=await response.json();
  if(response.status===409&&body.code==='session.err.duplicate'){duplicate(true);return;}
  if(!response.ok){errorText.textContent=response.status===401?error.dataset.credentials:error.dataset.failed;error.hidden=false;password.value='';duplicate(false);return;}
  if(typeof body.token!=='string'||!body.token)throw Error('Invalid login response');
  try{saveToken(body.token);}catch{
   // If tab storage is unavailable, undo the newly created session before returning.
   await fetch(form.dataset.logoutUrl,{method:'POST',headers:{Authorization:'Bearer '+body.token}}).catch(()=>{});
   throw Error('Token storage unavailable');
  }
  password.value='';location.replace(form.dataset.successUrl);
 }catch{errorText.textContent=error.dataset.failed;error.hidden=false;password.value='';duplicate(false);}
 finally{setBusy(false);}
}
form.addEventListener('submit',e=>{e.preventDefault();signIn(false);});
force.addEventListener('click',()=>signIn(true));
cancel.addEventListener('click',()=>{password.value='';error.hidden=true;duplicate(false);});
toggle.hidden=false;toggle.addEventListener('click',()=>{const show=password.type==='password';password.type=show?'text':'password';toggle.setAttribute('aria-pressed',String(show));toggle.setAttribute('aria-label',show?toggle.dataset.hide:toggle.dataset.show);toggle.querySelector('i').className=show?'las la-eye-slash':'las la-eye';});
const caps=document.getElementById('caps-warning');
password.addEventListener('keyup',e=>{caps.hidden=!e.getModifierState('CapsLock');});
password.addEventListener('blur',()=>caps.hidden=true);
language.value=language.dataset.locale?.startsWith('en')?'en_US':'ko_KR';
language.addEventListener('change',()=>{password.value='';const url=new URL(location.href);url.searchParams.set('lang',language.value);location.assign(url.href);});
window.addEventListener('pagehide',()=>{password.value='';});
window.addEventListener('pageshow',()=>{setBusy(false);password.value='';duplicate(false);});
if(new URL(location.href).searchParams.has('logout'))clearToken();
setBusy(false);
