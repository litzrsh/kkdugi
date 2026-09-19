import test from 'node:test';
import assert from 'node:assert/strict';
import {createApi} from '../../main/resources/static/js/api/index.mjs';
import {saveToken,getToken,clearToken} from '../../main/resources/static/js/auth/session.mjs';
const values=new Map();
globalThis.sessionStorage={getItem:key=>values.get(key),setItem:(key,value)=>values.set(key,value),removeItem:key=>values.delete(key)};
let redirect;
globalThis.location={replace:url=>redirect=url};
test('authenticated API sends tab token and preserves context path',async()=>{
 saveToken('test-token');let captured;
 const api=createApi({basePath:'/kk/'},async(url,options)=>{captured={url,options};return new Response('{}',{status:200});});
 await api.list('code',{page:1,pageSize:20});
 assert.equal(captured.url,'/kk/api/v1.0/admin/code');
 assert.equal(captured.options.headers.Authorization,'Bearer test-token');
 assert.equal(JSON.parse(captured.options.body).page,1);
});
test('401 removes stale token and returns to context-aware login',async()=>{
 saveToken('expired-token');
 const api=createApi({basePath:'/kk'},async()=>new Response('{"code":"auth.err.unauthorized"}',{status:401}));
 await assert.rejects(()=>api.list('code',{}),error=>error.status===401);
 assert.equal(getToken(),'');assert.equal(redirect,'/kk/login');
});
test('cleared token is not sent on subsequent requests',async()=>{
 clearToken();let headers;
 const api=createApi({},async(url,options)=>{headers=options.headers;return new Response(null,{status:204});});
 await api.request('/api/v1.0/auth/logout','POST');
 assert.equal(headers.Authorization,undefined);
});
