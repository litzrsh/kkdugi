import test from 'node:test';
import assert from 'node:assert/strict';
import {createApi} from '../../main/resources/static/js/api/index.mjs';
import {flatten,prepareLocalizedRow,prepareBatch,menuDescendants,filterMenuRows,isProtected} from '../../main/resources/static/js/domain/batch.mjs';
test('current session/auth routes are separate from admin menu CRUD',async()=>{
 const requests=[];const api=createApi({basePath:'/kk'},async(url,options)=>{requests.push({url,...options});return new Response('[]');});
 await api.menus();await api.request('/api/v1.0/auth/logout','POST');await api.list('menu',{page:7});await api.persist('menu',{insert:[],update:[],delete:[]});
 assert.deepEqual(requests.map(r=>[r.url,r.method]),[['/kk/api/v1.0/menu','GET'],['/kk/api/v1.0/auth/logout','POST'],['/kk/api/v1.0/admin/menu','GET'],['/kk/api/v1.0/admin/menu/persist','POST']]);assert.equal(requests[2].body,undefined);
});
test('root code sends null parent and excludes optional blank translations',()=>{
 const row={id:'',parentId:'',code:'ROOT',locale:{ko_KR:{name:'코드',remarks:''},en_US:{name:'',remarks:''}}};
 const sent=prepareLocalizedRow('code',row);assert.equal(sent.parentId,null);assert.deepEqual(Object.keys(sent.locale),['ko_KR']);assert.ok(row.locale.en_US);
});
test('existing translations cannot be silently cleared, but new languages can be added',()=>{
 const original={code:'system.msg.test',locale:{ko_KR:'원문'}};
 assert.throws(()=>prepareLocalizedRow('i18n',{...original,locale:{ko_KR:'',en_US:'English'}},original),e=>e.validationKey==='required');
 assert.deepEqual(prepareLocalizedRow('i18n',{...original,locale:{ko_KR:'원문',en_US:'English'}},original).locale,{ko_KR:'원문',en_US:'English'});
 assert.throws(()=>prepareLocalizedRow('menu',{locale:{en_US:{label:'',remarks:'Description without name'}}}),e=>e.validationKey==='required');
});
const tree=[{id:'root',parentId:null,locale:{en_US:{label:'Folder'}},children:[{id:'child',parentId:'root',locale:{en_US:{label:'Child'}},children:[{id:'leaf',parentId:'child',program:'custom',locale:{en_US:{label:'Leaf'}},children:null}]}]}];
test('nullable menu children flatten and search preserves ancestors',()=>{
 const rows=flatten(tree);assert.deepEqual(rows.map(r=>r._depth),[0,1,2]);assert.deepEqual(filterMenuRows(rows,'Leaf','en_US').map(r=>r.id),['root','child','leaf']);assert.deepEqual(menuDescendants(rows,[rows[1]]).map(r=>r.id),['child','leaf']);
});
test('cascade delete sends only the ancestor while retaining immutable parent and close flag on update',()=>{
 const rows=flatten(tree);const batch=prepareBatch('menu',{insert:[],update:[],delete:rows},rows);assert.deepEqual(batch.delete.map(r=>r.id),['root']);
 const edit={...rows[1],close:'N',locale:{en_US:{label:'Renamed'}}};const update=prepareBatch('menu',{insert:[],update:[edit],delete:[]},rows).update[0];assert.equal(update.parentId,'root');assert.equal(update.close,'N');assert.equal(update._depth,undefined);
});
test('system menus and their ancestors retain deletion protection',()=>{
 const rows=[{id:'system',path:'/system'},{id:'codes',path:'/system/codes',program:'admin/code'}];assert.ok(isProtected(rows[0],rows));assert.ok(isProtected(rows[1],rows));
});
test('menu route is allowed without a trailing slash while the retired session prefix and lookalikes are rejected',async()=>{
 const api=createApi({basePath:'/kk'},async()=>new Response('[]'));
 await api.request('/api/v1.0/menu','GET');
 await assert.rejects(()=>api.request('/api/v1.0/session/menu','GET'),/Invalid API path/);
 await assert.rejects(()=>api.request('/api/v1.0/menus','GET'),/Invalid API path/);
});
