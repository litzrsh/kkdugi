import {getToken,clearToken,loginUrl} from '../auth/session.mjs';
import {translate,labels,keyOf} from './i18n.mjs';
import * as domain from './domain.mjs';
import {createApi} from './api.mjs';
const config=window.KKDUGI||{};
const root=(config.basePath||'').replace(/\/$/,'');
const {reactive,createApp}=window.Vue;
const state=reactive({locale:config.locale||'ko_KR',notice:'',dialogs:[],busy:false});
const services={config,state,...domain,t:key=>config.mode==='preview'?translate(state.locale,key):((config.messages?.[state.locale]||config.messages||{})[keyOf(key)]??keyOf(key)),languages:config.languages||[],statuses:config.statuses||[],api:config.mode==='preview'?(await import('./demo.mjs')).createDemoApi():createApi(config)};
services.logout=async()=>{await services.api.request('/api/v1.0/admin/auth/logout','POST');clearToken();location.replace(loginUrl(config.basePath)+'?logout');};
let noticeTimer;
services.notify=key=>{state.notice=services.t(key);clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>state.notice='',4500);};
services.errorText=e=>services.t(({0:'network',400:'required',401:'unauthorized',403:'forbidden',404:'not_found',409:'conflict'})[e.status]||'request_failed');
let dialogId=0;
services.dialog={open:options=>new Promise((resolve,reject)=>state.dialogs.push({...options,id:++dialogId,resolve,reject})),confirm:async options=>(await services.dialog.open({kind:'confirm',...options})).status==='submitted',alert:options=>services.dialog.open({kind:'alert',...options})};
services.settle=(entry,result)=>{const i=state.dialogs.findIndex(d=>d.id===entry.id);if(i>=0)state.dialogs.splice(i,1);entry.resolve(result);};
const options={moduleCache:{vue:window.Vue},getFile:async url=>{const r=await fetch(url,{credentials:'same-origin'});if(!r.ok)throw Error(`SFC ${r.status}`);return r.text();},addStyle:()=>{throw Error('Use shared admin.css');}};
if(config.mode!=='preview'&&!getToken()){location.replace(loginUrl(config.basePath));}else try{const component=await window['vue3-sfc-loader'].loadModule(root+'/admin/App.vue',options);const app=createApp(component);app.provide('kkdugi',services);app.mount('#app');window.addEventListener('pagehide',()=>{clearTimeout(noticeTimer);app.unmount();},{once:true});}catch(error){const host=document.getElementById('app');host.textContent=services.t('load_error');console.error(error);}
