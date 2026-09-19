import {getToken,clearToken,loginUrl} from './auth/session.mjs';
import {keyOf} from './i18n/messages.mjs';
import * as domain from './domain/batch.mjs';
import * as navigation from './runtime/navigation.mjs';
import {createSfcRuntime} from './runtime/sfc-loader.mjs';
import {createApi} from './api/index.mjs';
const config=window.KKDUGI||{};
const {reactive,createApp}=window.Vue;
const state=reactive({locale:config.locale||'ko_KR',notice:'',dialogs:[],busy:false});
config.onUnauthorized=()=>window.dispatchEvent(new Event('kkdugi:unauthorized'));
const messages=config.messages?.[state.locale]||config.messages||{};
const services={config,state,...domain,navigation,menuIcon:navigation.menuIcon,t:key=>messages[keyOf(key)]??keyOf(key),message:key=>messages[key]??key,languages:config.languages||[],statuses:config.statuses||[],api:createApi(config)};
services.runtime=createSfcRuntime({basePath:config.basePath,vue:window.Vue,loader:window['vue3-sfc-loader'],api:services.api});
services.logout=async()=>{await services.api.request('/api/v1.0/auth/logout','POST');clearToken();config.onUnauthorized();location.replace(loginUrl(config.basePath)+'?logout');};
let noticeTimer;
services.notify=key=>{state.notice=services.t(key);clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>state.notice='',4500);};
services.errorText=e=>e.body?.message||services.t(({0:'network',400:'required',401:'unauthorized',403:'forbidden',404:'not_found',409:'conflict'})[e.status]||'request_failed');
let dialogId=0;
services.dialog={open:options=>new Promise(resolve=>state.dialogs.push({...options,id:++dialogId,resolve})),confirm:async options=>(await services.dialog.open({kind:'confirm',...options})).status==='submitted',alert:options=>services.dialog.open({kind:'alert',...options})};
services.settle=(entry,result)=>{if(!entry)return;const i=state.dialogs.findIndex(d=>d.id===entry.id);if(i<0)return;state.dialogs.splice(i,1);entry.resolve(result);};
services.cancelDialogs=ownerId=>{for(const entry of [...state.dialogs])if(ownerId===undefined||entry.ownerId===ownerId)services.settle(entry,{status:'cancelled',reason:'owner-disposed'});if(!state.dialogs.length)document.body.classList.remove('dialog-open');};
if(!getToken()){location.replace(loginUrl(config.basePath));}else try{
 const component=await services.runtime.shell();const app=createApp(component);app.provide('kkdugi',services);app.mount('#app');
 window.addEventListener('pagehide',()=>{clearTimeout(noticeTimer);app.unmount();},{once:true});
 // A page restored from the back/forward cache must re-check its session and menus.
 window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
}catch{document.getElementById('app').textContent=services.t('load_error');}
