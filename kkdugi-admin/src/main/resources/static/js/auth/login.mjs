import {requestLogin} from './login-request.mjs';
const form=document.getElementById('login-form'),username=document.getElementById('username'),password=document.getElementById('password'),submit=document.getElementById('login-submit'),toggle=document.querySelector('.password-toggle'),language=document.getElementById('login-language'),caps=document.getElementById('caps-warning'),error=document.getElementById('login-error');
const dialog=document.getElementById('duplicate-dialog'),confirm=document.getElementById('confirm-login'),cancel=document.getElementById('cancel-login');
const passwordDialog=document.getElementById('password-dialog'),passwordForm=document.getElementById('password-form'),newPassword=document.getElementById('new-password'),confirmPassword=document.getElementById('confirm-password'),extend=document.getElementById('extend-password'),passwordError=document.getElementById('password-error');
const label=submit.querySelector('span'),normalLabel=label.textContent;
let busy=false,controller,pendingAction='';
function setBusy(value){busy=value;for(const el of [submit,username,password,toggle,language,...passwordForm.elements,confirm,cancel])el.disabled=value;form.setAttribute('aria-busy',String(value));passwordForm.setAttribute('aria-busy',String(value));label.textContent=value?submit.dataset.busyLabel:normalLabel;}
function concealPassword(){password.type='password';toggle.setAttribute('aria-pressed','false');toggle.setAttribute('aria-label',toggle.dataset.show);toggle.querySelector('i').className='las la-eye';caps.hidden=true;}
function resetPassword(){password.value='';newPassword.value='';confirmPassword.value='';pendingAction='';concealPassword();}
function showError(message){dialog.close();passwordDialog.close();resetPassword();error.querySelector('span').textContent=message;error.hidden=false;password.focus();}
function passwordProblem(message){passwordError.textContent=message;passwordError.hidden=false;newPassword.focus();}
function passwordChallenge(kind){pendingAction='';concealPassword();newPassword.value='';confirmPassword.value='';passwordError.hidden=true;extend.hidden=kind!=='password_expired';document.getElementById('password-reason').textContent=kind==='password_required'?passwordDialog.dataset.initial:passwordDialog.dataset.expired;passwordDialog.showModal();(kind==='password_expired'?extend:newPassword).focus();}
async function signIn(force=false,action=''){
 if(busy||dialog.open||!form.reportValidity())return;
 if(action==='change'){
  if(!passwordForm.reportValidity())return;
  if(newPassword.value!==confirmPassword.value){passwordProblem(passwordDialog.dataset.mismatch);return;}
  if(newPassword.value.length<8||new TextEncoder().encode(newPassword.value).length>72||newPassword.value===password.value){passwordProblem(passwordDialog.dataset.invalid);return;}
 }
 const fields=new FormData(form);fields.set('force',String(force));if(action)fields.set('passwordAction',action);if(action==='change')fields.set('newPassword',newPassword.value);
 error.hidden=true;passwordError.hidden=true;document.getElementById('login-logout').hidden=true;
 setBusy(true);controller=new AbortController();const current=controller;
 try{
  const result=await requestLogin(form.action,fields,{signal:current.signal});if(current.signal.aborted)return;setBusy(false);
  if(result.status==='success'){resetPassword();passwordDialog.close();setBusy(true);location.replace(result.url);}
  else if(result.status==='duplicate'&&!force){pendingAction=action;concealPassword();passwordDialog.close();dialog.showModal();cancel.focus();}
  else if(result.status==='password_required'||result.status==='password_expired'){passwordChallenge(result.status);}
  else if(result.status==='password_invalid'&&action==='change'){if(!passwordDialog.open)passwordDialog.showModal();passwordProblem(passwordDialog.dataset.invalid);}
  else if(result.status==='blocked'){showError(error.dataset[result.reason]||error.dataset.invalid);}
  else showError(result.status==='error'?error.dataset.invalid:error.dataset.failed);
 }catch(e){if(!current.signal.aborted){setBusy(false);showError(error.dataset.failed);}}
 finally{fields.delete('password');fields.delete('newPassword');if(controller===current)controller=undefined;}
}
form.addEventListener('submit',event=>{event.preventDefault();void signIn();});
passwordForm.addEventListener('submit',event=>{event.preventDefault();void signIn(false,'change');});
extend.addEventListener('click',()=>void signIn(false,'extend'));
confirm.addEventListener('click',()=>{dialog.close();void signIn(true,pendingAction);});
function cancelSignIn(){if(busy)return;dialog.close();passwordDialog.close();resetPassword();password.focus();}
cancel.addEventListener('click',cancelSignIn);document.getElementById('cancel-password').addEventListener('click',cancelSignIn);
for(const el of [dialog,passwordDialog])el.addEventListener('cancel',event=>{event.preventDefault();cancelSignIn();});
toggle.hidden=false;toggle.addEventListener('click',()=>{const show=password.type==='password';password.type=show?'text':'password';toggle.setAttribute('aria-pressed',String(show));toggle.setAttribute('aria-label',show?toggle.dataset.hide:toggle.dataset.show);toggle.querySelector('i').className=show?'las la-eye-slash':'las la-eye';});
password.addEventListener('keyup',e=>{caps.hidden=!e.getModifierState('CapsLock');});password.addEventListener('blur',()=>caps.hidden=true);
// Thymeleaf selects the current locale from /SYS/LANG; retain its exact extra1 value.
language.addEventListener('change',()=>{resetPassword();const url=new URL(location.href);for(const key of ['duplicate','username','error','logout'])url.searchParams.delete(key);url.searchParams.set('lang',language.value);location.assign(url.href);});
window.addEventListener('pagehide',()=>{controller?.abort();dialog.close();passwordDialog.close();resetPassword();});
window.addEventListener('pageshow',()=>{dialog.close();passwordDialog.close();setBusy(false);resetPassword();});
setBusy(false);(username.value?password:username).focus();
