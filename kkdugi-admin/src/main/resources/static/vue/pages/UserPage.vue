<template><div class="user-page" :inert="loading||actionBusy||undefined">
 <div class="page-heading"><div><h1 tabindex="-1">{{title||t('aduser')}}</h1><p>{{remarks||t('user_desc')}}</p></div><div class="heading-icon"><i aria-hidden="true" class="las la-users"></i></div></div>
 <form class="search-panel" @submit.prevent="search"><div class="panel-heading"><span><i aria-hidden="true" class="las la-filter"></i> {{t('filters')}}</span><button type="button" class="text-button filter-toggle" @click="filtersOpen=!filtersOpen" :aria-expanded="filtersOpen" :aria-label="t('filters')"><i aria-hidden="true" :class="filtersOpen?'las la-angle-up':'las la-angle-down'"></i></button></div><div class="search-fields" v-show="filtersOpen">
  <div class="field"><label class="label" for="user-username">{{t('user_username')}}</label><input id="user-username" class="input" v-model="filters.username" maxlength="100" autocomplete="off"></div>
  <div class="field"><label class="label" for="user-status">{{t('status')}}</label><div class="select is-fullwidth"><select id="user-status" v-model="filters.status"><option value="">{{t('all')}}</option><option v-for="status in statuses" :key="status.code" :value="status.code">{{status.label}}</option></select></div></div>
  <div class="field"><label class="label" for="user-name">{{t('name')}}</label><input id="user-name" class="input" v-model="filters.name" maxlength="200" autocomplete="off"></div>
  <div class="search-actions"><button type="button" class="button" @click="reset"><i aria-hidden="true" class="las la-undo-alt"></i>{{t('reset')}}</button><button class="button is-primary"><i aria-hidden="true" class="las la-search"></i>{{t('search')}}</button></div>
 </div><div v-if="!filtersOpen" class="collapsed-search"><span>{{Object.values(filters).filter(Boolean).join(' · ')||t('all')}}</span><button class="button is-primary">{{t('search')}}</button></div></form>
 <div v-if="error" class="error-panel page-error" role="alert"><span>{{error}}</span><button class="text-button" @click="runLoad">{{t('retry')}}</button></div>
 <section class="list-panel" :aria-label="t('aduser')+' '+t('list')" :aria-busy="loading"><div class="list-toolbar"><div class="list-title"><h2>{{t('aduser')}} <span class="count-chip">{{total}}</span></h2></div><div class="toolbar-actions"><button v-if="canWrite&&statuses.length" class="button" :disabled="!selected.length" @click="changeStatus">{{t('user_status_change')}} <span v-if="selected.length">({{selected.length}})</span></button><button class="icon-button refresh-button" :aria-label="t('refresh')" @click="runLoad"><i aria-hidden="true" class="las la-sync-alt"></i></button><button v-if="canWrite&&statuses.length" class="button is-primary is-light" @click="edit()"><i aria-hidden="true" class="las la-plus"></i>{{t('add')}}</button></div></div>
  <div class="grid-zone"><Grid ref="grid" @selection="selected=$event" :rows="rows" :columns="columns" :locale="state.locale" :title="t('aduser')" :loading="loading" :selectable="canWrite"/><div v-if="!loading&&!rows.length" class="empty-state"><i aria-hidden="true" class="las la-inbox"></i><h3>{{t('empty')}}</h3><p>{{t('empty_hint')}}</p><button v-if="canWrite&&statuses.length" class="button" @click="edit()">{{t('add')}}</button></div></div>
  <div class="grid-footer"><span>{{t('total')}} <strong>{{total}}</strong> {{t('items')}}</span><div class="pagination-controls"><label class="page-size"><select :value="pageSize" @change="resize(+$event.target.value)" :aria-label="t('page_size')"><option v-for="size in [20,50,100,200]" :key="size" :value="size">{{size}} / {{t('page')}}</option></select></label><button class="icon-button" :disabled="page<=1" @click="move(page-1)" :aria-label="t('previous')"><i aria-hidden="true" class="las la-angle-left"></i></button><span class="current-page">{{page}}</span><span class="page-total">/ {{Math.max(1,pages)}}</span><button class="icon-button" :disabled="page>=pages" @click="move(page+1)" :aria-label="t('next')"><i aria-hidden="true" class="las la-angle-right"></i></button></div></div>
 </section><div class="below-grid"><p><i aria-hidden="true" class="las la-info-circle"></i>{{t('user_edit_hint')}}</p><span class="muted">kkdugi admin</span></div>
</div></template>
<script setup>
import {ref,computed,inject,onMounted,onBeforeUnmount} from 'vue';
import Grid from '../components/Grid.vue';
import {userFields,validateUser,authorityItems,authorityChanges,imageUrl} from '@js/domain/user.mjs';
const props=defineProps({permissions:Object,menuId:String,title:String,remarks:String});
const s=inject('kkdugi'),{t,state}=s,api=s.api.user;
const canWrite=computed(()=>props.permissions?.['20']===true),canDelete=computed(()=>props.permissions?.['30']===true);
const filters=ref({username:'',status:'',name:''}),applied=ref({}),rows=ref([]),page=ref(1),pageSize=ref(20),pages=ref(1),total=ref(0),loading=ref(false),actionBusy=ref(false),error=ref(''),filtersOpen=ref(true);
const grid=ref(null),selected=ref([]),statuses=ref([]);
let disposed=false,generation=0,request,actionRequest;
const open=options=>s.dialog.open({...options,ownerId:props.menuId});
const errorText=e=>e.validationKey?t(e.validationKey):s.errorText(e);
async function load(){
 request?.abort();request=new AbortController();const ticket=++generation;loading.value=true;error.value='';
 try{const options={signal:request.signal};const [result,codes]=await Promise.all([api.list({...applied.value,page:page.value,pageSize:pageSize.value},options),s.api.codes('UserStatus',{...options,enum:true,locale:state.locale})]);if(disposed||ticket!==generation)return;
 if(!Array.isArray(codes)||!codes.length)throw Error(t('user_status_unavailable'));
 statuses.value=codes.map(({code,name})=>({code,label:name}));
 grid.value?.clear();selected.value=[];rows.value=result.contents.map(row=>({...row,_key:row.id}));total.value=result.totalItems;pages.value=result.totalPages;
 if(page.value>Math.max(1,pages.value)){page.value=Math.max(1,pages.value);return await load();}
 }catch(e){if(e.name!=='AbortError'&&!disposed&&ticket===generation){error.value=errorText(e);throw e;}}finally{if(ticket===generation)loading.value=false;}
}
async function runLoad(){try{await load();}catch{}}
async function search(){applied.value=Object.fromEntries(Object.entries(filters.value).filter(([,v])=>v.trim()).map(([k,v])=>[k,v.trim()]));page.value=1;await runLoad();}
async function reset(){filters.value={username:'',status:'',name:''};await search();}
async function move(value){page.value=value;await runLoad();}
async function resize(value){pageSize.value=value;page.value=1;await runLoad();}
async function perform(task){
 if(actionBusy.value||loading.value||disposed||!statuses.value.length)return;actionBusy.value=true;actionRequest=new AbortController();
 try{const result=await task({signal:actionRequest.signal});if(result?.status==='submitted'&&!disposed){s.notify('user_saved');try{await load();}catch{rows.value=[];error.value=t('saved_reload');}}}
 catch(e){if(!disposed&&e.name!=='AbortError')await open({kind:'alert',title:t('notice'),message:errorText(e)});}
 finally{actionRequest?.abort();actionBusy.value=false;}
}
function edit(row){return perform(async options=>{
 const detail=row?await api.detail(row.id,options):{username:'',name:'',email:'',image:'',remarks:'',status:'20'};if(disposed)return;
 const fields=[
  {path:'image',label:t('user_image'),type:'profile',full:true,maxLength:500},
  {path:'username',label:t('user_username'),required:true,maxLength:100,readonly:!!row,hint:row?t('user_username_locked'):undefined},
  {path:'name',label:t('name'),required:true,maxLength:200},
  {path:'email',label:t('user_email'),type:'email',required:true,maxLength:200},
  {path:'status',label:t('status'),type:'select',required:true,options:statuses.value.map(status=>({value:status.code,label:status.label}))},
  {path:'remarks',label:t('remarks'),type:'textarea',full:true,maxLength:1000}
 ];
 if(row)fields.push({path:'lastLoginAt',label:t('user_last_login'),readonly:true},{path:'lastChangePasswordAt',label:t('user_password_changed'),readonly:true});
 return open({kind:'form',title:t(row?(canWrite.value?'edit':'view'):'add')+' · '+t('aduser'),subtitle:row?.name,initial:{...userFields(detail),lastLoginAt:detail.lastLoginAt||'—',lastChangePasswordAt:detail.lastChangePasswordAt||'—'},fields,readonly:!canWrite.value,message:row?undefined:t('user_temporary_hint'),validate:value=>{const key=validateUser(value,statuses.value);return key?t(key):'';},onSubmit:value=>{if(!canWrite.value)throw Error(t('forbidden'));return row?api.save(row.id,userFields(value),options):api.create(userFields(value),options);}});
});}
function authorities(row){return perform(async options=>{
 const rights={canWrite:canWrite.value,canDelete:canDelete.value};
 const [assigned,candidates]=await Promise.all([api.authorities(row.id,options),rights.canWrite?api.authorityCandidates(row.id,'',options):Promise.resolve([])]);if(disposed)return;
 const items=[...authorityItems(assigned,{...rights,assigned:true}),...authorityItems(candidates,rights).filter(item=>!assigned.some(old=>old.id===item.id))];
 return open({kind:'form',title:t('user_authorities'),subtitle:row.name+' · '+row.username,wide:true,mapping:'authorities',items,readonly:!rights.canWrite&&!rights.canDelete,message:t('user_authority_hint'),search:rights.canWrite?async query=>authorityItems(await api.authorityCandidates(row.id,query,options),rights):undefined,validate:values=>{try{authorityChanges(assigned,values,rights);return '';}catch(e){return errorText(e);}},onSubmit:values=>{const changes=authorityChanges(assigned,values,rights);if(!Object.values(changes).some(list=>list.length))return assigned;return api.saveAuthorities(row.id,changes,options);}});
});}
function resetPassword(row){if(!canWrite.value)return;return perform(options=>open({kind:'confirm',title:t('user_reset_password'),message:row.name+' · '+row.username,description:t('user_temporary_hint'),submitLabel:t('user_reset_password'),onSubmit:()=>api.resetPassword([row.id],options)}));}
function changeStatus(){if(!canWrite.value||!selected.value.length)return;const ids=selected.value.map(row=>row.id);return perform(options=>open({kind:'form',title:t('user_status_change'),subtitle:ids.length+' '+t('items'),message:t('user_status_hint'),initial:{status:selected.value.every(row=>row.status===selected.value[0].status)?selected.value[0].status:'20'},fields:[{path:'status',label:t('status'),type:'select',required:true,options:statuses.value.map(status=>({value:status.code,label:status.label}))}],validate:value=>statuses.value.some(status=>status.code===value.status)?'':t('required'),onSubmit:value=>api.changeStatus(ids,value.status,options)}));}
function remove(row){if(!canDelete.value)return;return perform(options=>open({kind:'confirm',title:t('confirm_delete'),message:row.name+' · '+row.username,description:t('user_delete_hint'),danger:true,submitLabel:t('delete'),onSubmit:()=>api.remove(row.id,options)}));}
function button(label,icon,handler,danger=false){const el=document.createElement('button');el.type='button';el.className='row-action'+(danger?' danger':'');el.setAttribute('aria-label',label);el.title=label;const i=document.createElement('i');i.className=icon;i.setAttribute('aria-hidden','true');const text=document.createElement('span');text.textContent=label;el.append(i,text);el.onclick=handler;return el;}
function identity(row){const el=document.createElement('div');el.className='user-identity';const avatar=document.createElement('span');avatar.className='mapping-avatar';avatar.setAttribute('aria-hidden','true');const fallback=()=>{avatar.replaceChildren();const icon=document.createElement('i');icon.className='las la-user';avatar.append(icon);};const url=imageUrl(row.image,location.href);if(url){const img=document.createElement('img');img.src=url;img.alt='';img.referrerPolicy='no-referrer';img.onerror=fallback;avatar.append(img);}else fallback();const name=document.createElement('span');name.className='user-identity-name';name.textContent=row.name;el.append(avatar,name);return el;}
const center={headerClass:'grid-center-header',cellClass:'grid-center-cell'};
const columns=computed(()=>[
 {field:'username',headerName:t('user_username'),width:165,cellClass:'mono-cell'},
 {field:'name',headerName:t('name'),width:200,cellRenderer:p=>identity(p.data)},
 {field:'email',headerName:t('user_email'),width:225},
 {field:'status',headerName:t('status'),width:110,...center,cellRenderer:p=>{const el=document.createElement('span');el.className='status-badge '+(p.value==='20'?'active':'neutral');el.textContent=statuses.value.find(status=>status.code===p.value)?.label||p.value;return el;}},
 {colId:'authorities',headerName:t('authorities'),width:105,...center,cellRenderer:p=>button(t('authorities'),'las la-user-shield',()=>authorities(p.data))},
 {field:'passwordStatus',headerName:t('user_password_status'),width:175,...center,valueFormatter:p=>p.value?t('password_status_'+p.value):'—'},
 {field:'lastLoginAt',headerName:t('user_last_login'),width:180,valueFormatter:p=>p.value||'—'},
 {colId:'actions',headerName:t('actions'),width:canWrite.value?360:190,...center,cellRenderer:p=>{const el=document.createElement('div');el.className='row-actions';el.append(button(t(canWrite.value?'edit':'view'),'las la-pen',()=>edit(p.data)));if(canWrite.value)el.append(button(t('user_reset_password'),'las la-key',()=>resetPassword(p.data)));if(canDelete.value)el.append(button(t('delete'),'las la-trash-alt',()=>remove(p.data),true));return el;}}
]);

function beforeUnload(event){if(actionBusy.value||state.dialogs.some(dialog=>dialog.ownerId===props.menuId)){event.preventDefault();event.returnValue='';}}
defineExpose({beforeLeave:()=>!actionBusy.value&&!state.dialogs.some(dialog=>dialog.ownerId===props.menuId)});
onMounted(()=>{runLoad();window.addEventListener('beforeunload',beforeUnload);});
onBeforeUnmount(()=>{disposed=true;generation++;request?.abort();actionRequest?.abort();s.cancelDialogs(props.menuId);window.removeEventListener('beforeunload',beforeUnload);});
</script>
