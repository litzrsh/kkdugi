import {flatten,menuLabel} from './batch.mjs';
export const rbacFlags=['10','20','30','40'];
export const authorityTypes=['ROLE','PLAN'];
export const assignableRoles=['ADMIN','USER','ANONYMOUS'];
export function roleChoices(original){return original?.role==='SYS_ADMIN'?['SYS_ADMIN']:original?.role&&!assignableRoles.includes(original.role)?[original.role,...assignableRoles]:assignableRoles;}
export const isSystemAuthority=row=>row?.role==='SYS_ADMIN';
const fail=key=>{throw Object.assign(new Error(key),{validationKey:key});};
export function authorityFields(row){return {role:row.role?.trim()||'',type:row.type,name:row.name?.trim()||'',remarks:row.remarks||'',use:row.use||'Y'};}
export function validateAuthority(row,original){
 const value=authorityFields(row);
 if(!value.role||!value.name||!authorityTypes.includes(value.type)||!['Y','N'].includes(value.use))return 'required';
 if(value.role.length>60||value.name.length>200||value.remarks.length>1000)return 'authority_length';
 if(value.role==='SYS_ADMIN'&&(value.type!=='ROLE'||original?.role!=='SYS_ADMIN'))return 'authority_system_locked';
 if(original?.role==='SYS_ADMIN'&&(value.role!=='SYS_ADMIN'||value.type!==original.type||value.use!==original.use))return 'authority_system_locked';
 if(!assignableRoles.includes(value.role)&&value.role!==original?.role)return 'authority_role_invalid';
 return '';
}
export function menuAssignments(tree,locale){return flatten(tree).map(row=>{
 const assignable=!!row.program?.trim();const authorities=Object.fromEntries(rbacFlags.map(flag=>[flag,assignable&&row.authorities?.[flag]===true]));
 return {id:row.id,parentId:row.parentId,depth:row._depth,name:menuLabel(row,locale),subtitle:row.program||'',assignable,authorities,selected:Object.values(authorities).some(Boolean)};
});}
export function menuMappingPayload(items){return items.filter(item=>item.assignable).map(item=>({id:item.id,authorities:Object.fromEntries(rbacFlags.map(flag=>[flag,item.authorities?.[flag]===true]))})).filter(item=>Object.values(item.authorities).some(Boolean));}
export function localDate(now=new Date()){return [now.getFullYear(),String(now.getMonth()+1).padStart(2,'0'),String(now.getDate()).padStart(2,'0')].join('-');}
export function userAssignments(detail){return (detail.users||[]).map(user=>({...user,name:user.name||user.id,subtitle:user.id,image:user.image||null,selected:true,locked:isSystemAuthority(detail)}));}
export function userCandidates(users,today=localDate()){return users.map(user=>({id:user.id,name:user.name||user.username||user.id,image:user.image||null,subtitle:[user.username,user.id].filter(Boolean).join(' · '),selected:false,applyStartDate:today,applyEndDate:'9999-12-31'}));}
export function validDate(value){if(!/^\d{4}-\d{2}-\d{2}$/.test(value||'')||value.startsWith('0000'))return false;const date=new Date(value+'T00:00:00Z');return !Number.isNaN(date.getTime())&&date.toISOString().slice(0,10)===value;}
export function userMappingPayload(items,detail){
 const users=items.filter(item=>item.selected).map(({id,applyStartDate,applyEndDate})=>({id,applyStartDate,applyEndDate}));
 if(users.some(user=>!validDate(user.applyStartDate)||!validDate(user.applyEndDate)))fail('authority_dates_required');
 if(users.some(user=>user.applyEndDate<user.applyStartDate))fail('invalid_date');
 if(isSystemAuthority(detail)&&(detail.users||[]).some(old=>!users.some(user=>user.id===old.id&&user.applyStartDate===old.applyStartDate&&user.applyEndDate===old.applyEndDate)))fail('authority_system_users');
 return users;
}
export function filterMappingItems(items,query,tree=false){
 const needle=query.trim().toLowerCase();if(!needle)return items;
 const visible=new Set(items.filter(item=>[item.name,item.subtitle,item.id].join(' ').toLowerCase().includes(needle)).map(item=>item.id));
 if(tree){const map=new Map(items.map(item=>[item.id,item]));for(const item of items.filter(item=>visible.has(item.id))){let parent=map.get(item.parentId);const seen=new Set();while(parent&&!seen.has(parent.id)){seen.add(parent.id);visible.add(parent.id);parent=map.get(parent.parentId);}}}
 return items.filter(item=>visible.has(item.id));
}
export function mergeCandidates(items,candidates){const retained=items.filter(item=>item.selected||item.assigned);const ids=new Set(retained.map(item=>item.id));return [...retained,...candidates.filter(item=>!ids.has(item.id))];}
