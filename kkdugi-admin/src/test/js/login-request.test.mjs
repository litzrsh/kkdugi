import test from 'node:test';
import assert from 'node:assert/strict';
import {requestLogin} from '../../main/resources/static/js/auth/login-request.mjs';
const action='https://example.test/kk/api/v1.0/auth/login';
const result=url=>({ok:true,redirected:true,url});
test('form login follows cookie-setting redirect and preserves encoded credentials',async()=>{
 const fields=new URLSearchParams({username:'a+b',password:'p&= +한글',force:'false',_csrf:'csrf'});
 let options;
 const response=await requestLogin(action,fields,{transport:async(url,init)=>{assert.equal(url,action);options=init;return result('https://example.test/kk/');}});
 assert.equal(response.status,'success');assert.equal(response.url,'https://example.test/kk/');
 assert.equal(options.credentials,'same-origin');assert.equal(options.redirect,'follow');
 assert.equal(options.body.get('password'),'p&= +한글');assert.equal(options.body.get('_csrf'),'csrf');
 assert.equal(options.body.get('force'),'false');assert.equal(options.headers.Authorization,undefined);
});
test('duplicate JSON is a challenge, explicit force resubmits the same form contract',async()=>{
 const fields=new URLSearchParams({username:'user',password:'pass',force:'false'});
 assert.deepEqual(await requestLogin(action,fields,{transport:async()=>Response.json({code:'session.err.duplicate',message:'duplicate'},{status:401})}),{status:'duplicate'});
 fields.set('force','true');
 assert.equal((await requestLogin(action,fields,{transport:async(url,init)=>{assert.equal(init.body.get('force'),'true');return result('https://example.test/kk/');}})).status,'success');
});
test('invalid credentials are distinguished from unexpected or failed responses',async()=>{
 assert.deepEqual(await requestLogin(action,[],{transport:async()=>Response.json({code:'system.err.default',message:'error'},{status:401})}),{status:'error'});
 for(const response of [result('https://other.test/kk/'),result('https://example.test/'),result('https://example.test/kk/login'),{...result('https://example.test/kk/'),ok:false},{...result('https://example.test/kk/'),redirected:false}]){
  await assert.rejects(requestLogin(action,[],{transport:async()=>response}));
 }
 for(const response of [new Response('bad',{status:401}),Response.json({message:'bad'},{status:401})])await assert.rejects(requestLogin(action,[],{transport:async()=>response}));
});
test('network failures propagate without automatically replaying a login',async()=>{
 let requests=0;
 await assert.rejects(requestLogin(action,[],{transport:async()=>{requests++;throw new TypeError('network');}}));
 assert.equal(requests,1);
});
test('navigation abort signal is forwarded',async()=>{
 const controller=new AbortController();controller.abort();
 await assert.rejects(requestLogin(action,[],{signal:controller.signal,transport:async(url,options)=>{assert.equal(options.signal,controller.signal);options.signal.throwIfAborted();}}),{name:'AbortError'});
});

test('login state challenges use known codes without reflecting arbitrary server messages',async()=>{
 for(const reason of ['pending','dormant','resigned','suspended'])assert.deepEqual(await requestLogin(action,[],{transport:async()=>Response.json({code:'auth.err.'+reason,message:'<script>unsafe</script>'},{status:401})}),{status:'blocked',reason});
 for(const status of ['password_required','password_expired','password_invalid'])assert.deepEqual(await requestLogin(action,[],{transport:async()=>Response.json({code:'auth.err.'+status},{status:401})}),{status});
});
test('password change and extension remain login form requests with secrets only in the body',async()=>{
 for(const passwordAction of ['change','extend'])await requestLogin(action,new URLSearchParams({username:'user',password:'current-password',passwordAction,newPassword:passwordAction==='change'?'new&password':''}),{transport:async(url,init)=>{
  assert.equal(url,action);assert.equal(init.body.get('passwordAction'),passwordAction);assert.equal(init.body.get('password'),'current-password');return result('https://example.test/kk/');
 }});
});
