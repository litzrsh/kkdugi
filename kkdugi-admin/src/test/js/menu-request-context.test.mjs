import test from 'node:test';
import assert from 'node:assert/strict';
import {createApi} from '../../main/resources/static/js/api/index.mjs';
import {createHttp} from '../../main/resources/static/js/api/http.mjs';
import {MENU_ID_HEADER,SHELL_MENU_ID} from '../../main/resources/static/js/api/request-context.mjs';
import {setTokenCookie} from './support/cookie-jar.mjs';
function fixture(){
 const calls=[];
 const transport=async(url,options)=>{calls.push({url,...options});return url.includes('/pragma/')?new Response('<template><p>Page</p></template>',{headers:{'Content-Type':'text/html'}}):Response.json([]);};
 return {api:createApi({basePath:'/kk'},transport),http:createHttp({},transport),calls};
}
test('every page transaction carries its originating menu ID without modifying payloads',async()=>{
 setTokenCookie('token');const {api,calls}=fixture(),page=api.forMenu('M_CODE');const batch={insert:[],update:[],delete:[]};
 await page.list('code',{page:2});await page.list('i18n',{page:1});await page.list('menu',{});
 await page.persist('code',batch);await page.persist('i18n',batch);await page.persist('menu',batch);
 await page.codes('/SYS/STATUS');await page.request('/api/v1.0/admin/menu','GET');
 assert.equal(calls.length,8);
 for(const call of calls){assert.equal(call.headers[MENU_ID_HEADER],'M_CODE');assert.equal(call.headers.Authorization,'Bearer token');}
 assert.deepEqual(JSON.parse(calls[0].body),{page:2});assert.deepEqual(JSON.parse(calls[3].body),batch);assert.equal(calls[2].body,undefined);
});
test('page and dialog callbacks keep their menu context through concurrent navigation',async()=>{
 const {api,calls}=fixture(),first=api.forMenu('M_FIRST'),second=api.forMenu('M_SECOND');
 const dialogSave=()=>first.persist('menu',{});
 await Promise.all([second.list('code',{}),dialogSave(),first.codes('/SYS')]);
 assert.deepEqual(calls.map(c=>c.headers[MENU_ID_HEADER]),['M_SECOND','M_FIRST','M_FIRST']);
 await first.list('menu',{}, {menuId:'M_OVERRIDE'});
 assert.equal(calls.at(-1).headers[MENU_ID_HEADER],'M_FIRST');
});
test('Pragma carries the requested menu instead of the previously active page',async()=>{
 const {api,http,calls}=fixture();const controller=new AbortController();
 await api.forMenu('M_OLD').pragma('M & 1',{menuId:'M_WRONG',signal:controller.signal});
 assert.equal(calls[0].url,'/kk/pragma/M%20%26%201');assert.equal(calls[0].headers[MENU_ID_HEADER],'M & 1');assert.equal(calls[0].signal,controller.signal);
 await assert.rejects(http.text('/pragma/M_NEW',{menuId:'M_OLD'}),/must match/);
 assert.equal(calls.length,1);
});
test('shell bootstrap is explicit and cannot be reused for privileged endpoints',async()=>{
 const {api,calls}=fixture();await api.menus();
 assert.equal(calls[0].headers[MENU_ID_HEADER],SHELL_MENU_ID);
 await assert.rejects(api.persist('menu',{}, {menuId:SHELL_MENU_ID}),/Shell context/);
 await assert.rejects(api.codes('/SYS',{menuId:SHELL_MENU_ID}),/Shell context/);
 await assert.rejects(api.request('/api/v1.0/menu','POST',undefined,{menuId:SHELL_MENU_ID}),/Shell context/);
 assert.throws(()=>api.forMenu(SHELL_MENU_ID),/reserved/);
 await api.forMenu('M_PAGE').menus();assert.equal(calls[1].headers[MENU_ID_HEADER],'M_PAGE');
});
test('only login/logout are exempt, even when called from a scoped page',async()=>{
 const {api,calls}=fixture();
 for(const client of [api,api.forMenu('M_PAGE')])for(const endpoint of ['login','logout'])await client.request('/api/v1.0/auth/'+endpoint+'?lang=en_US','POST');
 for(const call of calls)assert.equal(call.headers[MENU_ID_HEADER],undefined);
 await assert.rejects(api.request('/api/v1.0/auth/password','POST'),/menu ID/);
 await assert.rejects(api.request('/api/v1.0/auth/logout/extra','POST'),/menu ID/);
 assert.equal(calls.length,4);
});
test('missing or malformed IDs fail before network I/O; explicit context is supported',async()=>{
 const {api,calls}=fixture();
 for(const menuId of [undefined,null,'',' ',' M_ID','M_ID ','M\r\nInjected: x','M\u007f','한글','X'.repeat(61)]){
  await assert.rejects(api.list('code',{}, {menuId}),/menu ID/);
  assert.throws(()=>api.forMenu(menuId),/menu ID/);
 }
 assert.equal(calls.length,0);
 await api.request('/api/v1.0/admin/code','POST',{}, {menuId:'M_EXPLICIT'});
 assert.equal(calls[0].headers[MENU_ID_HEADER],'M_EXPLICIT');
});
