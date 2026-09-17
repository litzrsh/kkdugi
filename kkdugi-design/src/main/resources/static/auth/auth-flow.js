(() => {
 'use strict';
 const preview=document.body.dataset.preview==='true';
 const form=document.getElementById('flow-form');
 const cancel=document.getElementById('cancel-form');
 const submit=document.getElementById('flow-submit');
 const language=document.getElementById('login-language');
 const url=new URL(location.href);
 const locale=language.dataset.locale||url.searchParams.get('lang')||'ko_KR';
 const isPassword=form.dataset.kind==='password';
 let pending=false;
 let normalLabel=submit.querySelector('span').textContent;
 language.value=locale.startsWith('en')?'en_US':'ko_KR';
 language.addEventListener('change',()=>{const next=new URL(location.href);next.searchParams.set('lang',language.value);location.assign(next.href);});
 document.querySelectorAll('.password-toggle').forEach(toggle=>{
  toggle.hidden=false;
  const input=document.getElementById(toggle.getAttribute('aria-controls'));
  toggle.addEventListener('click',()=>{const show=input.type==='password';input.type=show?'text':'password';toggle.setAttribute('aria-pressed',String(show));toggle.setAttribute('aria-label',show?toggle.dataset.hide:toggle.dataset.show);toggle.querySelector('i').className=show?'las la-eye-slash':'las la-eye';});
 });
 const password=document.getElementById('newPassword');
 const confirmation=document.getElementById('confirmPassword');
 const mismatch=document.getElementById('password-mismatch');
 if(isPassword)for(const input of [password,confirmation])input.addEventListener('input',()=>{confirmation.setCustomValidity('');mismatch.hidden=true;});
 const reset=()=>{pending=false;document.querySelectorAll('button[type=submit]').forEach(b=>b.disabled=false);form.removeAttribute('aria-busy');submit.querySelector('span').textContent=normalLabel;};
 reset();window.addEventListener('pageshow',reset);
 form.addEventListener('submit',event=>{
  if(pending){event.preventDefault();return;}
  if(isPassword&&password.value!==confirmation.value){event.preventDefault();mismatch.hidden=false;confirmation.setCustomValidity(mismatch.textContent);confirmation.focus();confirmation.reportValidity();return;}
  if(preview){
   event.preventDefault();
   if(isPassword){password.value='';confirmation.value='';}
   if(isPassword&&url.searchParams.get('next')==='session'){location.assign('login-session.html?lang='+encodeURIComponent(locale));return;}
   document.getElementById('flow-complete').hidden=false;return;
  }
  pending=true;form.setAttribute('aria-busy','true');document.querySelectorAll('button[type=submit]').forEach(b=>b.disabled=true);submit.querySelector('span').textContent=submit.dataset.busyLabel;
 });
 cancel.addEventListener('submit',event=>{
  if(preview){event.preventDefault();location.assign('login.html?lang='+encodeURIComponent(locale));return;}
  if(pending){event.preventDefault();return;}
  pending=true;document.querySelectorAll('button[type=submit]').forEach(b=>b.disabled=true);
 });
 if(preview){
  if(isPassword&&url.searchParams.get('reason')==='expired'){const reason=document.getElementById('password-reason');reason.dataset.i18n='expired_reason';reason.textContent='비밀번호 사용 기간이 만료되었습니다. 새 비밀번호를 설정해야 로그인할 수 있습니다.';}
  if(locale.startsWith('en'))fetch('auth/login-messages.json').then(r=>r.json()).then(messages=>{
   document.documentElement.lang='en';document.title=messages[isPassword?'password_title':'session_title'][1];
   document.querySelectorAll('[data-i18n]').forEach(el=>el.textContent=messages[el.dataset.i18n][1]);
   normalLabel=messages[isPassword?'change_continue':'force_login'][1];
   document.querySelectorAll('.password-toggle').forEach(toggle=>{toggle.dataset.show=messages.show[1];toggle.dataset.hide=messages.hide[1];toggle.setAttribute('aria-label',toggle.getAttribute('aria-pressed')==='true'?messages.hide[1]:messages.show[1]);});
  }).catch(()=>{});
 }
})();
