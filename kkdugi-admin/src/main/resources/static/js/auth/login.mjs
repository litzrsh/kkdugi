import {requestLogin} from './login-request.mjs';
const form=document.getElementById('login-form');
const username=document.getElementById('username');
const password=document.getElementById('password');
const submit=document.getElementById('login-submit');
const toggle=document.querySelector('.password-toggle');
const language=document.getElementById('login-language');
const caps=document.getElementById('caps-warning');
const error=document.getElementById('login-error');
const dialog=document.getElementById('duplicate-dialog');
const confirm=document.getElementById('confirm-login');
const cancel=document.getElementById('cancel-login');
const label=submit.querySelector('span'),normalLabel=label.textContent;
let busy=false,controller;
function setBusy(value){busy=value;for(const element of [submit,username,password,toggle,language])element.disabled=value;form.setAttribute('aria-busy',String(value));label.textContent=value?submit.dataset.busyLabel:normalLabel;}
function resetPassword(){password.value='';password.type='password';toggle.setAttribute('aria-pressed','false');toggle.setAttribute('aria-label',toggle.dataset.show);toggle.querySelector('i').className='las la-eye';caps.hidden=true;}
function showError(message){resetPassword();error.querySelector('span').textContent=message;error.hidden=false;password.focus();}
async function signIn(force=false){
 if(busy||dialog.open||!form.reportValidity())return;
 const fields=new FormData(form);fields.set('force',String(force));
 error.hidden=true;document.getElementById('login-logout').hidden=true;
 setBusy(true);controller=new AbortController();const current=controller;
 try{
  const result=await requestLogin(form.action,fields,{signal:current.signal});
  if(current.signal.aborted)return;
  setBusy(false);
  if(result.status==='success'){resetPassword();setBusy(true);location.replace(result.url);}
  else if(result.status==='duplicate'&&!force){password.type='password';dialog.showModal();cancel.focus();}
  else showError(result.status==='error'?error.dataset.invalid:error.dataset.failed);
 }catch(e){if(!current.signal.aborted){setBusy(false);showError(error.dataset.failed);}}
 finally{fields.delete('password');if(controller===current)controller=undefined;}
}
form.addEventListener('submit',event=>{event.preventDefault();void signIn();});
confirm.addEventListener('click',()=>{dialog.close();void signIn(true);});
function cancelSignIn(){dialog.close();resetPassword();password.focus();}
cancel.addEventListener('click',cancelSignIn);
dialog.addEventListener('cancel',event=>{event.preventDefault();cancelSignIn();});
toggle.hidden=false;toggle.addEventListener('click',()=>{const show=password.type==='password';password.type=show?'text':'password';toggle.setAttribute('aria-pressed',String(show));toggle.setAttribute('aria-label',show?toggle.dataset.hide:toggle.dataset.show);toggle.querySelector('i').className=show?'las la-eye-slash':'las la-eye';});
password.addEventListener('keyup',e=>{caps.hidden=!e.getModifierState('CapsLock');});
password.addEventListener('blur',()=>caps.hidden=true);
language.value=language.dataset.locale?.startsWith('en')?'en_US':'ko_KR';
language.addEventListener('change',()=>{resetPassword();const url=new URL(location.href);for(const key of ['duplicate','username','error','logout'])url.searchParams.delete(key);url.searchParams.set('lang',language.value);location.assign(url.href);});
window.addEventListener('pagehide',()=>{controller?.abort();dialog.close();resetPassword();});
window.addEventListener('pageshow',()=>{setBusy(false);resetPassword();});
setBusy(false);
(username.value?password:username).focus();
