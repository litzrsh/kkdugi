<template><div class="authority-page" :inert="loading||actionBusy||undefined">
 <div class="page-heading"><div><div class="eyebrow">SYSTEM MANAGEMENT <span class="eyebrow-line"></span> ADMAUTH</div><h1 tabindex="-1">{{title||t('admauth')}}</h1><p>{{remarks||t('auth_desc')}}</p></div><div class="heading-icon"><i aria-hidden="true" class="las la-user-shield"></i></div></div>
 <p class="authority-note"><i class="las la-info-circle" aria-hidden="true"></i>{{t('authority_session_hint')}}</p>
 <form class="search-panel" @submit.prevent="search"><div class="panel-heading"><span><i aria-hidden="true" class="las la-filter"></i> {{t('filters')}}</span><button type="button" class="text-button filter-toggle" @click="filtersOpen=!filtersOpen" :aria-expanded="filtersOpen" :aria-label="t('filters')"><i aria-hidden="true" :class="filtersOpen?'las la-angle-up':'las la-angle-down'"></i></button></div><div class="search-fields" v-show="filtersOpen">
  <div class="field"><label class="label" for="authority-role">{{t('role_code')}}</label><input id="authority-role" class="input" v-model="filters.role" maxlength="60" autocomplete="off"></div>
  <div class="field"><label class="label" for="authority-type">{{t('type')}}</label><div class="select is-fullwidth"><select id="authority-type" v-model="filters.type"><option value="">{{t('all')}}</option><option v-for="type in authorityTypes" :key="type" :value="type">{{t('authority_type_'+type.toLowerCase())}}</option></select></div></div>
  <div class="field"><label class="label" for="authority-name">{{t('name')}}</label><input id="authority-name" class="input" v-model="filters.name" maxlength="200" autocomplete="off"></div>
  <div class="search-actions"><button type="button" class="button" @click="reset"><i aria-hidden="true" class="las la-undo-alt"></i>{{t('reset')}}</button><button class="button is-primary"><i aria-hidden="true" class="las la-search"></i>{{t('search')}}</button></div>
 </div><div v-if="!filtersOpen" class="collapsed-search"><span>{{Object.values(filters).filter(Boolean).join(' · ')||t('all')}}</span><button class="button is-primary">{{t('search')}}</button></div></form>
 <div v-if="error" class="error-panel page-error" role="alert"><span>{{error}}</span><button class="text-button" @click="runLoad">{{t('retry')}}</button></div>
 <section class="list-panel" :aria-label="t('admauth')+' '+t('list')" :aria-busy="loading"><div class="list-toolbar"><div class="list-title"><h2>{{t('admauth')}} <span class="count-chip">{{total}}</span></h2></div><div class="toolbar-actions"><button class="icon-button refresh-button" :aria-label="t('refresh')" @click="runLoad"><i aria-hidden="true" class="las la-sync-alt"></i></button><button v-if="canWrite" class="button is-primary is-light" @click="edit()"><i aria-hidden="true" class="las la-plus"></i>{{t('add')}}</button></div></div>
  <div class="grid-zone"><Grid :rows="rows" :columns="columns" :locale="state.locale" :title="t('admauth')" :loading="loading" :selectable="false"/><div v-if="!loading&&!rows.length" class="empty-state"><i aria-hidden="true" class="las la-inbox"></i><h3>{{t('empty')}}</h3><p>{{t('empty_hint')}}</p><button v-if="canWrite" class="button" @click="edit()">{{t('add')}}</button></div></div>
  <div class="grid-footer"><span>{{t('total')}} <strong>{{total}}</strong> {{t('items')}}</span><div class="pagination-controls"><label class="page-size"><select :value="pageSize" @change="resize(+$event.target.value)" :aria-label="t('page_size')"><option v-for="size in [20,50,100,200]" :key="size" :value="size">{{size}} / {{t('page')}}</option></select></label><button class="icon-button" :disabled="page<=1" @click="move(page-1)" :aria-label="t('previous')"><i aria-hidden="true" class="las la-angle-left"></i></button><span class="current-page">{{page}}</span><span class="page-total">/ {{Math.max(1,pages)}}</span><button class="icon-button" :disabled="page>=pages" @click="move(page+1)" :aria-label="t('next')"><i aria-hidden="true" class="las la-angle-right"></i></button></div></div>
 </section><div class="below-grid"><p><i aria-hidden="true" class="las la-info-circle"></i>{{t('authority_edit_hint')}}</p><span class="muted">kkdugi admin</span></div>
</div></template>
<script setup>
import {ref,computed,inject,onMounted,onBeforeUnmount} from 'vue';
import Grid from '../components/Grid.vue';
import {authorityTypes,assignableRoles,roleChoices,rbacFlags,isSystemAuthority,authorityFields,validateAuthority,menuAssignments,menuMappingPayload,userAssignments,userCandidates,userMappingPayload} from '@js/domain/authority.mjs';
const props=defineProps({permissions:Object,menuId:String,title:String,remarks:String});
const s=inject('kkdugi'),{t,state}=s,api=s.api.authority;
const canWrite=computed(()=>props.permissions?.['20']===true),canDelete=computed(()=>props.permissions?.['30']===true);
const filters=ref({role:'',type:'',name:''}),applied=ref({}),rows=ref([]),page=ref(1),pageSize=ref(20),pages=ref(1),total=ref(0),loading=ref(false),actionBusy=ref(false),error=ref(''),filtersOpen=ref(true);
let disposed=false,generation=0,request,actionRequest;
const open=options=>s.dialog.open({...options,ownerId:props.menuId});
const errorText=e=>e.validationKey?t(e.validationKey):s.errorText(e);
async function load(){
 request?.abort();request=new AbortController();const ticket=++generation;loading.value=true;error.value='';
 try{const result=await api.list({...applied.value,page:page.value,pageSize:pageSize.value},{signal:request.signal});if(disposed||ticket!==generation)return;
 rows.value=result.contents.map(row=>({...row,_key:row.id}));total.value=result.totalItems;pages.value=result.totalPages;
 if(page.value>Math.max(1,pages.value)){page.value=Math.max(1,pages.value);return await load();}
 }catch(e){if(e.name!=='AbortError'&&!disposed&&ticket===generation){error.value=errorText(e);throw e;}}finally{if(ticket===generation)loading.value=false;}
}
async function runLoad(){try{await load();}catch{}}
async function search(){applied.value=Object.fromEntries(Object.entries(filters.value).filter(([,v])=>v.trim()).map(([k,v])=>[k,v.trim()]));page.value=1;await runLoad();}
async function reset(){filters.value={role:'',type:'',name:''};await search();}
async function move(value){page.value=value;await runLoad();}
async function resize(value){pageSize.value=value;page.value=1;await runLoad();}
async function perform(task){
 if(actionBusy.value||loading.value||disposed)return;actionBusy.value=true;actionRequest=new AbortController();
 try{const result=await task({signal:actionRequest.signal});if(result?.status==='submitted'&&!disposed){s.notify('authority_saved');try{await load();}catch{rows.value=[];error.value=t('saved_reload');}}}
 catch(e){if(!disposed&&e.name!=='AbortError')await open({kind:'alert',title:t('notice'),message:errorText(e)});}
 finally{actionRequest?.abort();actionBusy.value=false;}
}
function edit(row){return perform(async options=>{
 const detail=row?await api.detail(row.id,options):{role:'',type:'ROLE',name:'',remarks:'',use:'Y'};if(disposed)return;
 const fixed=isSystemAuthority(detail);
 const fields=[{path:'role',label:t('role_code'),type:'select',required:true,readonly:fixed,options:[{value:'',label:t('select_role'),disabled:true},...roleChoices(row?detail:undefined).map(value=>({value,label:assignableRoles.includes(value)||value==='SYS_ADMIN'?value+' · '+t('role_'+value.toLowerCase()):value+' · '+t('existing_role'),disabled:!assignableRoles.includes(value)}))]},{path:'type',label:t('type'),type:'select',readonly:fixed,options:authorityTypes.map(value=>({value,label:t('authority_type_'+value.toLowerCase())}))},{path:'name',label:t('name'),required:true,maxLength:200},{path:'use',label:t('use'),type:'select',readonly:fixed,options:[{value:'Y',label:t('enabled')},{value:'N',label:t('disabled')}]},{path:'remarks',label:t('remarks'),type:'textarea',full:true,maxLength:1000}];
 return open({kind:'form',title:t(row?(canWrite.value?'edit':'view'):'add')+' · '+t('admauth'),subtitle:row?.name,initial:authorityFields(detail),fields,readonly:!canWrite.value,message:fixed?t('authority_system_locked'):undefined,validate:value=>{const key=validateAuthority(value,row?detail:undefined);return key?t(key):'';},onSubmit:value=>{if(!canWrite.value)throw Error(t('forbidden'));return row?api.save(row.id,authorityFields(value),options):api.create(authorityFields(value),options);}});
});}
function menus(row){return perform(async options=>{
 const [detail,tree]=await Promise.all([api.detail(row.id,options),api.menus(row.id,options)]);if(disposed)return;
 return open({kind:'form',title:t('authority_menu_title'),subtitle:detail.name,wide:true,mapping:'menus',permissionFlags:rbacFlags,items:menuAssignments(tree,state.locale),readonly:!canWrite.value,message:t('authority_menu_hint'),onSubmit:items=>{if(!canWrite.value)throw Error(t('forbidden'));return api.save(row.id,{...authorityFields(detail),menus:menuMappingPayload(items)},options);}});
});}
function users(row){return perform(async options=>{
 const [detail,candidates]=await Promise.all([api.detail(row.id,options),canWrite.value?api.users(row.id,'',options):Promise.resolve([])]);if(disposed)return;
 const assigned=userAssignments(detail).map(item=>({...item,assigned:true})),ids=new Set(assigned.map(item=>item.id));
 return open({kind:'form',title:t('authority_user_title'),subtitle:detail.name,wide:true,mapping:'users',items:[...assigned,...userCandidates(candidates).filter(item=>!ids.has(item.id))],readonly:!canWrite.value,message:t('authority_user_hint')+(isSystemAuthority(detail)?' '+t('authority_system_users'):''),search:canWrite.value?async query=>userCandidates(await api.users(row.id,query,options)):undefined,validate:items=>{try{userMappingPayload(items,detail);return '';}catch(e){return errorText(e);}},onSubmit:items=>{if(!canWrite.value)throw Error(t('forbidden'));return api.save(row.id,{...authorityFields(detail),users:userMappingPayload(items,detail)},options);}});
});}
function remove(row){if(!canDelete.value||isSystemAuthority(row))return;return perform(async options=>open({kind:'confirm',title:t('confirm_delete'),message:row.name+' · '+row.role,description:t('permanent'),danger:true,submitLabel:t('delete'),onSubmit:()=>api.remove(row.id,options)}));}
function button(label,icon,handler,danger=false){const el=document.createElement('button');el.type='button';el.className='row-action'+(danger?' danger':'');el.setAttribute('aria-label',label);el.title=label;const i=document.createElement('i');i.className=icon;const text=document.createElement('span');text.textContent=label;el.append(i,text);el.onclick=handler;return el;}
const columns=computed(()=>[
 {field:'role',headerName:t('role_code'),width:170,cellClass:'mono-cell'},
 {field:'type',headerName:t('type'),width:120,valueFormatter:p=>t('authority_type_'+p.value.toLowerCase())},
 {field:'name',headerName:t('name'),width:210},
 {field:'use',headerName:t('status'),width:105,headerClass:'grid-center-header',cellClass:'grid-center-cell',cellRenderer:p=>{const el=document.createElement('span');el.className='status-badge '+(p.value==='Y'?'active':'neutral');el.textContent=t(p.value==='Y'?'enabled':'disabled');return el;}},
 {colId:'menus',headerName:t('menu'),width:110,headerClass:'grid-center-header',cellClass:'grid-center-cell',cellRenderer:p=>button(t('menu'),'las la-sitemap',()=>menus(p.data))},
 {colId:'users',headerName:t('users'),width:110,headerClass:'grid-center-header',cellClass:'grid-center-cell',cellRenderer:p=>button(t('users'),'las la-users',()=>users(p.data))},
 {colId:'actions',headerName:t('actions'),width:190,minWidth:180,headerClass:'grid-center-header',cellClass:'grid-center-cell',cellRenderer:p=>{const el=document.createElement('div');el.className='row-actions';el.append(button(t(canWrite.value?'edit':'view'),'las la-pen',()=>edit(p.data)));if(canDelete.value&&!isSystemAuthority(p.data))el.append(button(t('delete'),'las la-trash-alt',()=>remove(p.data),true));return el;}}
]);
function beforeUnload(event){if(actionBusy.value||state.dialogs.some(dialog=>dialog.ownerId===props.menuId)){event.preventDefault();event.returnValue='';}}
defineExpose({beforeLeave:()=>!actionBusy.value&&!state.dialogs.some(dialog=>dialog.ownerId===props.menuId)});
onMounted(()=>{runLoad();window.addEventListener('beforeunload',beforeUnload);});
onBeforeUnmount(()=>{disposed=true;generation++;request?.abort();actionRequest?.abort();s.cancelDialogs(props.menuId);window.removeEventListener('beforeunload',beforeUnload);});
</script>
