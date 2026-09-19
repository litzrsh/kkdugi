import test from 'node:test';
import assert from 'node:assert/strict';
import {markMenuDeleted,restoreMenuRows,filterMenuRows,menuLabel,prepareBatch,payload,validate,clone} from '../../main/resources/static/js/domain/batch.mjs';
const tree=()=>[{id:'root',parentId:null,locale:{en_US:{label:'Parent'}}},{id:'child',parentId:'root',locale:{ko_KR:{label:'자식'}}},{id:'leaf',parentId:'child',locale:{en_US:{label:'Leaf'}}}];
test('overlapping child then parent deletion restores the complete subtree from any row',()=>{
 const rows=tree();markMenuDeleted(rows,[rows[1]],'first');markMenuDeleted(rows,[rows[0]],'second');
 assert.ok(rows.every(r=>r._deleted));restoreMenuRows(rows,rows[1]);assert.ok(rows.every(r=>!r._deleted));
});
test('menu search retains ancestors and editing state including untranslated menu labels',()=>{
 const rows=tree();rows[1].locale.ko_KR.label='수정 자식';
 assert.deepEqual(filterMenuRows(rows,'수정','en_US').map(r=>r.id),['root','child']);
 assert.equal(menuLabel(rows[1],'en_US'),'수정 자식');assert.equal(menuLabel(undefined,'en_US'),'');
});
test('cascade batch deletes only parent and restores edited descendants without losing changes',()=>{
 const original=tree(),rows=clone(original);rows[1].locale.ko_KR.label='Changed';
 markMenuDeleted(rows,[rows[0]],'group');assert.deepEqual(prepareBatch('menu',payload(rows,original),original).delete.map(r=>r.id),['root']);
 restoreMenuRows(rows,rows[0]);assert.deepEqual(payload(rows,original).update.map(r=>r.id),['child']);
});
test('program validation accepts groups and nested paths but rejects paths Pragma cannot render',()=>{
 const menu={locale:{en_US:{label:'Menu'}},sort:0};
 for(const program of ['',null,'admin/menu','custom/my_page-2'])assert.equal(validate('menu',{...menu,program},[]),'');
 for(const program of ['../escape','/admin/menu','admin//menu','admin/menu.vue','bad path','admin/menu\n'])assert.equal(validate('menu',{...menu,program},[]),'invalid_program');
});
