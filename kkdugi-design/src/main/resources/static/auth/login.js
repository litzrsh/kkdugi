(() => {
 'use strict';
 const form=document.getElementById('login-form');
 const password=document.getElementById('password');
 const toggle=document.querySelector('.password-toggle');
 const submit=document.getElementById('login-submit');
 const language=document.getElementById('login-language');
 const preview=document.body.dataset.preview==='true';
 let submitting=false;
 if(preview)submit.disabled=false;
 toggle.hidden=false;
 toggle.addEventListener('click',()=>{
  const show=password.type==='password';
  password.type=show?'text':'password';
  toggle.setAttribute('aria-pressed',String(show));
  toggle.setAttribute('aria-label',show?toggle.dataset.hide:toggle.dataset.show);
  toggle.querySelector('i').className=show?'las la-eye-slash':'las la-eye';
 });
 const caps=document.getElementById('caps-warning');
 password.addEventListener('keyup',e=>{caps.hidden=!e.getModifierState('CapsLock');});
 password.addEventListener('blur',()=>{caps.hidden=true;});
 form.addEventListener('submit',event=>{
  if(preview){
   event.preventDefault();
   const scenario=document.getElementById('preview-scenario')?.value||'normal';
   const lang=encodeURIComponent(language.value);
   password.value='';
   if(scenario==='duplicate'){location.assign('login-session.html?lang='+lang);return;}
   if(['initial','expired','combined'].includes(scenario)){location.assign('login-password.html?reason='+(scenario==='expired'?'expired':'initial')+'&lang='+lang+(scenario==='combined'?'&next=session':''));return;}
   document.getElementById('preview-result').hidden=false;return;
  }
  if(submitting){event.preventDefault();return;}
  submitting=true;submit.disabled=true;form.setAttribute('aria-busy','true');submit.querySelector('span').textContent=submit.dataset.busyLabel;
 });
 let normalLabel=submit.querySelector('span').textContent;
 window.addEventListener('pageshow',()=>{submitting=false;submit.disabled=false;form.removeAttribute('aria-busy');submit.querySelector('span').textContent=normalLabel;});
 const current=new URL(location.href);
 const locale=language.dataset.locale||current.searchParams.get('lang')||'ko_KR';
 language.value=locale.startsWith('en')?'en_US':'ko_KR';
 language.addEventListener('change',()=>{const url=new URL(location.href);url.searchParams.set('lang',language.value);location.assign(url.href);});
 if(preview){
  document.getElementById('login-error').hidden=current.searchParams.get('error')!=='true';
  document.getElementById('login-logout').hidden=current.searchParams.get('logout')!=='true';
  if(locale.startsWith('en'))fetch('auth/login-messages.json').then(r=>r.json()).then(messages=>{
   document.documentElement.lang='en';document.title=messages.title[1];
   normalLabel=messages.submit[1];
   document.querySelectorAll('[data-i18n]').forEach(el=>{el.textContent=messages[el.dataset.i18n][1];});
   document.querySelectorAll('[data-placeholder]').forEach(el=>{el.placeholder=messages[el.dataset.placeholder][1];});
   toggle.dataset.show=messages.show[1];toggle.dataset.hide=messages.hide[1];toggle.setAttribute('aria-label',password.type==='password'?messages.show[1]:messages.hide[1]);
  }).catch(()=>{});
 }
})();
