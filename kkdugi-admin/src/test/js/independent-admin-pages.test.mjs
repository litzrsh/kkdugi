import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import * as batch from '../../main/resources/static/js/domain/batch.mjs';
import {createSfcRuntime} from '../../main/resources/static/js/runtime/sfc-loader.mjs';

const assets=path.resolve(import.meta.dirname,'../../main/resources/static');
const context=vm.createContext({console,TextEncoder,TextDecoder,URL,URLSearchParams,setTimeout,clearTimeout,atob,btoa,AbortController,crypto});
context.window=context;context.self=context;context.globalThis=context;
context.addEventListener=()=>{};context.removeEventListener=()=>{};
for(const file of ['vue.global.prod.js','vue3-sfc-loader.js'])vm.runInContext(await fs.readFile(path.join(assets,'vendor',file),'utf8'),context);
const Vue=context.Vue;
const node=()=>({children:[],style:{},props:{}});
const renderer=Vue.createRenderer({
 createElement:node,createText:text=>({...node(),text}),createComment:node,
 setText:(el,text)=>{el.text=text;},setElementText:(el,text)=>{el.text=text;},
 patchProp:(el,key,old,value)=>{el.props[key]=value;},
 insert:(el,parent,anchor)=>{el.parent=parent;const i=parent.children.indexOf(anchor);parent.children.splice(i<0?parent.children.length:i,0,el);},
 remove:el=>{const siblings=el.parent?.children;if(siblings)siblings.splice(siblings.indexOf(el),1);},
 parentNode:el=>el.parent,nextSibling:el=>el.parent?.children[el.parent.children.indexOf(el)+1],
});
const normalize=value=>JSON.parse(JSON.stringify(value));

async function mountPage(name,{data=[],permissions={'20':true,'30':true}}={}){
 const calls=[],dialogs=[],notifications=[];
 let dialogResult='cancelled';
 const service={...batch,t:key=>key,message:key=>key,errorText:e=>e.message,
  state:Vue.reactive({locale:'en_US',dialogs:[]}),languages:Vue.reactive([]),
  api:{list:async(resource,params)=>{calls.push({resource,params:normalize(params)});return resource==='menu'?data:{contents:data,totalItems:data.length,totalPages:1};},
   codes:async()=>[{extra1:'en_US',name:'English'},{extra1:'ko_KR',name:'한국어'}],
   persist:async(resource,value)=>{calls.push({resource,batch:normalize(value)});}},
  dialog:{open:async options=>{dialogs.push(options);return {status:dialogResult};}},
  notify:key=>notifications.push(key),cancelDialogs:()=>{},
 };
 const runtime=createSfcRuntime({origin:'http://localhost',vue:Vue,loader:context['vue3-sfc-loader'],
  api:{pragma:async()=>`<template><Page :permissions="permissions" menu-id="test-menu"/></template><script setup>import Page from '@vue/pages/${name}.vue';defineProps({permissions:Object});</script>`},
  transport:async url=>new Response(url.endsWith('/Grid.vue')?'<template><div/></template><script setup>defineProps({rows:Array,columns:Array});defineExpose({stop:()=>{},clear:()=>{},edit:()=>{}});</script>':await fs.readFile(path.join(assets,url.slice(1)),'utf8')),
 });
 const app=renderer.createApp(await runtime.page(name),{permissions});app.provide('kkdugi',service);
 const root=app.mount(node());
 await new Promise(resolve=>setImmediate(resolve));await Vue.nextTick();
 const page=root.$.subTree.component;
 return {page:page.setupState,leave:()=>page.exposed.beforeLeave(),calls,dialogs,notifications,
  confirm:()=>{dialogResult='submitted';},unmount:()=>app.unmount()};
}

test('code page keeps parent navigation, localized details and its own save endpoint',async t=>{
 const h=await mountPage('CodePage',{data:[{id:'C1',code:'ROOT',path:'/ROOT',locale:{en_US:{name:'Root'}},sort:0,use:'Y'}]});t.after(h.unmount);
 assert.equal(h.calls[0].resource,'code');assert.equal(h.calls[0].params.parentId,null);
 await h.page.descend(h.page.rows[0]);assert.equal(h.calls.at(-1).params.parentId,'C1');
 await h.page.editRow();const row=h.page.rows[0];row.code='CHILD';
 h.page.cellEdited({data:row,colDef:{field:'code'}});
 assert.equal(h.page.rows[0].path,'/ROOT/CHILD');assert.equal(row.parentId,'C1');
 await h.page.editCodeDetails(h.page.rows[0]);const form=h.dialogs.at(-1);
 assert.equal(form.translation,'name');assert.equal(form.ownerId,'test-menu');
 form.onSubmit({locale:{en_US:{name:'Child',remarks:''}}});
 await h.page.save();const sent=h.calls.find(call=>call.batch);
 assert.equal(sent.resource,'code');assert.equal(sent.batch.insert[0].parentId,'C1');
 assert.equal(sent.batch.insert[0].locale.en_US.name,'Child');
});

test('message page edits dynamic language columns and rejects duplicate codes',async t=>{
 const h=await mountPage('MessagePage',{data:[{code:'system.msg.existing',locale:{en_US:'Hello'}}]});t.after(h.unmount);
 assert.equal(h.calls[0].resource,'i18n');assert.equal('parentId' in h.calls[0].params,false);
 assert.deepEqual(normalize(h.page.columns.filter(c=>c.colId?.startsWith('locale_')).map(c=>c.colId)),['locale_en_US','locale_ko_KR']);
 await h.page.editRow();const row=h.page.rows[0];row.code='system.msg.existing';row.locale.en_US='New';
 await h.page.save();assert.equal(h.page.error,'duplicate');assert.equal(h.calls.some(call=>call.batch),false);
 row.code='system.msg.new';h.page.cellEdited({data:row});await h.page.save();
 assert.equal(h.page.error,'');
 const sent=h.calls.find(call=>call.batch);assert.equal(sent.resource,'i18n');assert.equal(sent.batch.insert[0].locale.en_US,'New');
});

test('menu page filters locally, creates children and restores cascade deletions',async t=>{
 const h=await mountPage('MenuPage',{data:[{id:'M1',parentId:null,program:'',locale:{en_US:{label:'Parent'}},children:[{id:'M2',parentId:'M1',program:'custom/child',locale:{en_US:{label:'Child'}}}]}]});t.after(h.unmount);
 assert.equal(h.calls[0].resource,'menu');assert.equal(h.page.rows.length,2);
 h.page.filters.name='Child';await h.page.search();assert.equal(h.calls.length,1);assert.equal(h.page.visibleRows.length,2);
 await h.page.editRow(null,h.page.rows[0]);const form=h.dialogs.at(-1);
 assert.equal(form.translation,'label');assert.equal(form.initial.parentId,'M1');
 form.onSubmit({...form.initial,locale:{en_US:{label:'New child'}}});
 await h.page.save();const sent=h.calls.find(call=>call.batch);assert.equal(sent.resource,'menu');assert.equal(sent.batch.insert[0].parentId,'M1');
 h.confirm();await h.page.removeRows([h.page.rows[0]]);assert.ok(h.page.rows.every(row=>row._deleted));
 h.page.restore(h.page.rows[1]);assert.ok(h.page.rows.every(row=>!row._deleted));
});

for(const name of ['CodePage','MessagePage','MenuPage'])test(`${name} preserves write permissions and the unsaved navigation guard`,async t=>{
 const readonly=await mountPage(name,{permissions:{'20':false,'30':false}});t.after(readonly.unmount);
 await readonly.page.editRow();assert.equal(readonly.page.rows.length,0);assert.equal(readonly.dialogs.length,0);assert.equal(await readonly.leave(),true);
 const writable=await mountPage(name);t.after(writable.unmount);
 await writable.page.editRow();
 if(name==='MenuPage'){const form=writable.dialogs.at(-1);form.onSubmit({...form.initial,locale:{en_US:{label:'New'}}});}
 assert.equal(await writable.leave(),false);assert.equal(writable.dialogs.at(-1).title,'unsaved');
 writable.page.rows=[];writable.confirm();assert.equal(await writable.leave(),true);
});
