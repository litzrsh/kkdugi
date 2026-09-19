import test from 'node:test';
import assert from 'node:assert/strict';
import {createApi} from '../../main/resources/static/js/api/index.mjs';
import {normalizeMenus,menuIdFromHash,menuHash,createPageRequests,menuIcon} from '../../main/resources/static/js/runtime/navigation.mjs';
import {getToken} from '../../main/resources/static/js/auth/session.mjs';
import {setTokenCookie} from './support/cookie-jar.mjs';
import {validate} from '../../main/resources/static/js/domain/batch.mjs';
globalThis.location={replace:()=>{}};
test('menu tree accepts null children, sorts siblings and preserves server IDs',()=>{
 const tree=normalizeMenus([{id:'folder',children:[{id:'B',sort:2,children:null},{id:'A',sort:1,children:[]}]}]);
 assert.deepEqual(tree[0].children.map(n=>n.id),['A','B']);assert.deepEqual(tree[0].children[1].children,[]);
 assert.equal(menuIdFromHash(menuHash('M & 한글')),'M & 한글');assert.equal(menuIdFromHash('#admcode'),null);
 assert.equal(menuIcon('https://unknown'),'las la-folder');
});
test('Pragma uses authenticated text request with context path and no redirect/cache',async()=>{
 setTokenCookie('token');let captured;
 const api=createApi({basePath:'/kk/'},async(url,options)=>{captured={url,options};return new Response('<template><p>Screen</p></template>',{headers:{'Content-Type':'text/html;charset=UTF-8'}});});
 assert.match(await api.pragma('M & 1'),/Screen/);assert.equal(captured.url,'/kk/pragma/M%20%26%201');
 assert.equal(captured.options.headers.Accept,'application/json, text/html;q=0.9');assert.equal(captured.options.headers['X-Requested-With'],'XMLHttpRequest');assert.equal(captured.options.headers.Authorization,'Bearer token');assert.equal(captured.options.cache,'no-store');assert.equal(captured.options.redirect,'error');
});
test('Pragma refuses login HTML and reports 404 without pretending it is a session failure',async()=>{
 const api=createApi({},async()=>new Response('<!doctype html><html>Login</html>',{headers:{'content-type':'text/html'}}));
 await assert.rejects(api.pragma('M'),e=>e.status===502);
 setTokenCookie('still-present');const missing=createApi({},async()=>new Response(null,{status:404}));
 await assert.rejects(missing.pragma('M'),e=>e.status===404);assert.equal(getToken(),'still-present');
});
test('401 with non-JSON body still clears token and calls disposal',async()=>{
 setTokenCookie('expired');let disposed=false;
 const api=createApi({onUnauthorized:()=>disposed=true},async()=>new Response('<html>unauthorized</html>',{status:401}));
 await assert.rejects(api.menus(),e=>e.status===401);assert.equal(getToken(),'');assert.ok(disposed);
});
test('batch empty success and both error formats are preserved',async()=>{
 const empty=createApi({},async()=>new Response(''));assert.equal(await empty.persist('code',{}),null);
 for(const body of [{errors:[{code:'x',reason:'code.err.duplicate'}]},[{code:'err.default',message:'fail'}]]){
 const api=createApi({},async()=>new Response(JSON.stringify(body),{status:400}));await assert.rejects(api.persist('i18n',{}),e=>e.errors.length===1);
 }
 assert.throws(()=>empty.list('user',{}),/Unsupported/);
});
test('single backend error retains its localized message',async()=>{
 const api=createApi({},async()=>new Response(JSON.stringify({code:'code.err.duplicate',message:'Duplicate code'}),{status:409}));
 await assert.rejects(api.persist('code',{}),e=>e.body.message==='Duplicate code'&&e.status===409);
});
test('new common codes follow current uppercase contract without rewriting existing IDs',()=>{
 const row={locale:{en_US:{name:'Test'}},sort:0};
 for(const code of ['lower','_ABC','ABC_','A__B','A-B','ABC\n'])assert.equal(validate('code',{...row,code},[]),'invalid_common_code');
 assert.equal(validate('code',{...row,code:'SYSTEM_20'},[]),'');
 assert.equal(validate('code',{...row,id:'existing',code:'legacy'},[]),'');
});
test('superseded and cancelled compilation never wins a later screen',async()=>{
 const pending=new Map();const queue=createPageRequests((id,signal)=>new Promise(resolve=>pending.set(id,{resolve,signal})));
 const first=queue.open('A'),second=queue.open('B');assert.ok(pending.get('A').signal.aborted);
 pending.get('B').resolve('B component');assert.equal((await second).component,'B component');
 pending.get('A').resolve('A component');assert.equal(await first,null);
 const third=queue.open('C');queue.cancel();pending.get('C').resolve('C component');assert.equal(await third,null);
});
