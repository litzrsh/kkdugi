import test from 'node:test';
import assert from 'node:assert/strict';
import {userFields,validateUser,imageUrl,authorityItems,authorityChanges} from '../../main/resources/static/js/domain/user.mjs';
import {createApi} from '../../main/resources/static/js/api/index.mjs';
const old=[{id:'SYS',role:'SYS_ADMIN',applyStartDate:'2020-01-01',applyEndDate:'9999-12-31'}];
const statuses=[{code:'20',name:'Normal'}];
test('user payload limits fields, trims values and validates status/profile protocol',()=>{
 const value=userFields({username:' alice ',name:' Alice ',email:'a@b.com',password:'secret',lastLoginAt:'now',status:'20'});
 assert.deepEqual(Object.keys(value),['username','name','email','remarks','image','status']);assert.equal(value.username,'alice');assert.equal(validateUser(value,statuses),'');
 assert.equal(validateUser({...value,image:'javascript:alert(1)'},statuses),'user_image_invalid');assert.equal(validateUser({...value,status:'99'},statuses),'required');assert.equal(validateUser({...value,name:'a'.repeat(201)},statuses),'user_length');
 assert.equal(imageUrl('/avatar.png','https://example.com/'),'https://example.com/avatar.png');assert.equal(imageUrl('data:image/png;base64,abc','https://example.com/'),'');
});
test('authority batch sends only changed IDs and dates; no implicit replacement',()=>{
 const items=authorityItems(old,{assigned:true,canWrite:true,canDelete:true});assert.deepEqual(authorityChanges(old,items),{insert:[],update:[],delete:[]});
 items[0].applyEndDate='2030-12-31';items.push(...authorityItems([{id:'NEW',name:'New'}],{canWrite:true}));items[1].selected=true;
 const diff=authorityChanges(old,items,{canWrite:true});assert.deepEqual(diff.update,[{id:'SYS',applyStartDate:'2020-01-01',applyEndDate:'2030-12-31'}]);assert.deepEqual(Object.keys(diff.insert[0]),['id','applyStartDate','applyEndDate']);
});
test('authority date edits and removal honor separate permissions including SYS_ADMIN',()=>{
 const write=authorityItems(old,{assigned:true,canWrite:true});assert.equal(write[0].selectionLocked,true);assert.equal(write[0].datesLocked,false);
 const remove=authorityItems(old,{assigned:true,canDelete:true});assert.equal(remove[0].selectionLocked,false);assert.equal(remove[0].datesLocked,true);remove[0].selected=false;
 assert.deepEqual(authorityChanges(old,remove,{canDelete:true}),{insert:[],update:[],delete:[{id:'SYS'}]});assert.throws(()=>authorityChanges(old,remove,{canWrite:true}),/forbidden/);
 write[0].applyEndDate='2030-01-01';assert.throws(()=>authorityChanges(old,write,{canDelete:true}),/forbidden/);
 write[0].applyEndDate='2030-02-30';assert.throws(()=>authorityChanges(old,write,{canWrite:true}),/authority_dates_required/);
 write[0].applyEndDate='2019-01-01';assert.throws(()=>authorityChanges(old,write,{canWrite:true}),/invalid_date/);
});
test('all user API calls preserve the originating menu and exact endpoint bodies',async()=>{
 const calls=[];const api=createApi({basePath:'/kk'},async(url,options)=>{calls.push({url,...options});return new Response('{}');}).forMenu('M_USER').user;
 await api.list({page:1});await api.detail('U1');await api.create({username:'a'});await api.save('U1',{name:'A'});await api.remove('U1');await api.resetPassword(['U1']);await api.changeStatus(['U1'],'50');await api.authorities('U1');await api.saveAuthorities('U1',{delete:[{id:'A'}]});await api.authorityCandidates('U1','Admin');
 assert.ok(calls.every(c=>c.headers['X-Menu-Id']==='M_USER'));
 assert.deepEqual(calls.map(c=>[c.url.replace('/kk/api/v1.0/admin/user',''),c.method]),[['','POST'],['/U1','GET'],['/regist','POST'],['/U1','POST'],['/U1/delete','POST'],['/reset-password','POST'],['/change-status','POST'],['/U1/authorities','GET'],['/U1/authorities','POST'],['/U1/authority-candidates','POST']]);
 assert.deepEqual(JSON.parse(calls[5].body),{id:['U1']});assert.deepEqual(JSON.parse(calls[6].body),{id:['U1'],status:'50'});assert.deepEqual(JSON.parse(calls[9].body),{query:'Admin'});
});

test('user status validation follows the fetched enum list',()=>{
 const user={username:'alice',name:'Alice',email:'alice@example.com',status:'60'};
 assert.equal(validateUser(user,[{code:'60',name:'New server status'}]),'');
 assert.equal(validateUser(user,statuses),'required');
 assert.equal(validateUser({...user,status:'20'},[]),'required');
});
test('enum code lookup sends locale, cancellation and originating menu without changing DB lookup',async()=>{
 const calls=[],controller=new AbortController();const api=createApi({basePath:'/kk'},async(url,options)=>{calls.push({url,...options});return Response.json([{code:'20',name:'정상'}]);}).forMenu('M_USER');
 const codes=await api.codes('UserStatus',{enum:true,locale:'ko_KR',signal:controller.signal});
 assert.equal(calls[0].url,'/kk/api/v1.0/code?path=UserStatus&enum=true&lang=ko_KR');assert.equal(calls[0].headers['X-Menu-Id'],'M_USER');assert.equal(calls[0].signal,controller.signal);assert.equal(codes[0].name,'정상');
 await api.codes('/SYS/STATUS');assert.equal(calls[1].url,'/kk/api/v1.0/code?path=%2FSYS%2FSTATUS');
});
