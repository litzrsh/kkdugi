import test from 'node:test';
import assert from 'node:assert/strict';
import {createApi} from '../../main/resources/static/js/api/index.mjs';
import {setTokenCookie} from './support/cookie-jar.mjs';
import {authorityFields,validateAuthority,roleChoices,menuAssignments,menuMappingPayload,userAssignments,userCandidates,userMappingPayload,filterMappingItems,mergeCandidates,validDate} from '../../main/resources/static/js/domain/authority.mjs';

test('authority endpoints preserve request menu ID and exact replacement contracts',async()=>{
 setTokenCookie('token');const calls=[],signal=new AbortController().signal;
 const api=createApi({basePath:'/kk'},async(url,options)=>{calls.push({url,...options});return Response.json({});}).forMenu('M_AUTH').authority;
 const fields={role:'OP',type:'ROLE',name:'Operator',use:'Y'};
 await api.list({page:1,pageSize:20},{signal});await api.detail('A & 1',{signal});await api.create(fields,{signal});
 await api.save('A1',{...fields,users:[]},{signal});await api.remove('A1',{signal});await api.users('A1','kim',{signal});await api.menus('A1',{signal});
 assert.deepEqual(calls.map(c=>[c.url,c.method]),[
 ['/kk/api/v1.0/admin/authority','POST'],['/kk/api/v1.0/admin/authority/A%20%26%201','GET'],['/kk/api/v1.0/admin/authority/regist','POST'],
 ['/kk/api/v1.0/admin/authority/A1','POST'],['/kk/api/v1.0/admin/authority/A1/delete','POST'],['/kk/api/v1.0/admin/authority/A1/user','POST'],['/kk/api/v1.0/admin/authority/A1/menu','POST']]);
 for(const call of calls){assert.equal(call.headers['X-Menu-Id'],'M_AUTH');assert.equal(call.headers.Authorization,'Bearer token');assert.equal(call.signal,signal);}
 assert.deepEqual(JSON.parse(calls[2].body),fields);assert.deepEqual(JSON.parse(calls[3].body),{...fields,users:[]});assert.deepEqual(JSON.parse(calls[5].body),{query:'kim'});
 assert.equal(calls[1].body,undefined);assert.equal(calls[4].body,undefined);assert.equal(calls[6].body,undefined);
});
test('basic edits omit mappings, enforce lengths and reserved authority types',()=>{
 const detail={id:'A1',role:'OP',type:'ROLE',name:'Operator',remarks:null,use:'Y',users:[{id:'U1'}],menus:[{id:'M1'}]};
 assert.deepEqual(authorityFields(detail),{role:'OP',type:'ROLE',name:'Operator',remarks:'',use:'Y'});
 assert.equal(validateAuthority(detail,detail),'');assert.equal(validateAuthority({...detail,name:' '}),'required');
 assert.equal(validateAuthority({...detail,role:'a'.repeat(61)}),'authority_length');
 assert.equal(validateAuthority({...detail,role:'SYS_ADMIN',type:'PLAN'}),'authority_system_locked');
});
test('RBAC is numeric, group rows have no assignments and menu search retains ancestors',()=>{
 const tree=[{id:'G',locale:{ko_KR:{label:'그룹'}},authorities:{'10':true},children:[{id:'M',parentId:'G',program:'admin/code',locale:{en_US:{label:'Codes'}},authorities:{'10':true,'20':false,'40':true},children:null}]}];
 const items=menuAssignments(tree,'en_US');assert.equal(items[0].name,'그룹');assert.equal(items[0].assignable,false);assert.equal(items[0].selected,false);assert.equal(items[1].depth,1);
 assert.deepEqual(menuMappingPayload(items),[{id:'M',authorities:{'10':true,'20':false,'30':false,'40':true}}]);
 assert.deepEqual(filterMappingItems(items,'codes',true).map(r=>r.id),['G','M']);
 for(const flag of Object.keys(items[1].authorities))items[1].authorities[flag]=false;
 assert.deepEqual(menuMappingPayload(items),[]);
});
test('user selection survives candidate searches and emits complete validated date mappings',()=>{
 const detail={role:'OP',users:[{id:'U1',applyStartDate:'2026-01-01',applyEndDate:'9999-12-31'}]};
 const assigned=userAssignments(detail).map(r=>({...r,assigned:true}));
 const candidates=userCandidates([{id:'U2',username:'alice',name:'Alice'}],'2026-09-19');candidates[0].selected=true;
 const merged=mergeCandidates([...assigned,...candidates,...userCandidates([{id:'U3'}])],userCandidates([{id:'U4'}]));
 assert.deepEqual(merged.map(r=>r.id),['U1','U2','U4']);assert.equal(merged[1].applyStartDate,'2026-09-19');
 assert.equal(userMappingPayload(merged,detail).length,2);
 merged[1].applyEndDate='2020-01-01';assert.throws(()=>userMappingPayload(merged,detail),e=>e.validationKey==='invalid_date');
 merged[1].applyEndDate='';assert.throws(()=>userMappingPayload(merged,detail),e=>e.validationKey==='authority_dates_required');
 assert.equal(validDate('2026-02-30'),false);assert.equal(validDate('2024-02-29'),true);assert.equal(validDate('9999-12-31'),true);
 assigned[0].selected=false;assert.deepEqual(userMappingPayload(assigned,detail),[]);
});
test('SYS_ADMIN existing user assignments and dates cannot be removed or narrowed',()=>{
 const detail={role:'SYS_ADMIN',users:[{id:'ADMIN',applyStartDate:'2026-01-01',applyEndDate:'9999-12-31'}]};
 const items=userAssignments(detail);assert.equal(items[0].locked,true);
 assert.deepEqual(userMappingPayload(items,detail),detail.users);
 items[0].selected=false;assert.throws(()=>userMappingPayload(items,detail),e=>e.validationKey==='authority_system_users');
 items[0].selected=true;items[0].applyEndDate='2026-01-02';assert.throws(()=>userMappingPayload(items,detail),e=>e.validationKey==='authority_system_users');
});

test('role dropdown reserves SYS_ADMIN and retains legacy values without offering them on new entries',()=>{
 assert.deepEqual(roleChoices(),['ADMIN','USER','ANONYMOUS']);assert.deepEqual(roleChoices({role:'SYS_ADMIN'}),['SYS_ADMIN']);
 assert.deepEqual(roleChoices({role:'OLD_ROLE'}),['OLD_ROLE','ADMIN','USER','ANONYMOUS']);
 const base={role:'USER',name:'User',type:'ROLE',use:'Y'};
 for(const role of ['ADMIN','USER','ANONYMOUS'])assert.equal(validateAuthority({...base,role}),'');
 assert.equal(validateAuthority({...base,role:'SYS_ADMIN'}),'authority_system_locked');
 assert.equal(validateAuthority({...base,role:'OLD_ROLE'}),'authority_role_invalid');
 const system={...base,role:'SYS_ADMIN'};assert.equal(validateAuthority(system,system),'');
 assert.equal(validateAuthority({...system,role:'ADMIN'},system),'authority_system_locked');
});
test('assigned and candidate profiles use API names/images and never send them in mapping writes',()=>{
 const detail={role:'USER',users:[{id:'U1',name:'홍길동',image:'/profiles/one.png',applyStartDate:'2026-01-01',applyEndDate:'9999-12-31'}]};
 const [assigned]=userAssignments(detail);assert.equal(assigned.name,'홍길동');assert.equal(assigned.image,'/profiles/one.png');assert.equal(assigned.subtitle,'U1');
 assert.deepEqual(userMappingPayload([assigned],detail),[{id:'U1',applyStartDate:'2026-01-01',applyEndDate:'9999-12-31'}]);
 const [candidate]=userCandidates([{id:'U2',name:'홍길순',image:'https://example.com/avatar.jpg'}]);assert.equal(candidate.image,'https://example.com/avatar.jpg');
 assert.equal(userAssignments({users:[{id:'U3',name:null,image:null}]})[0].name,'U3');
});
