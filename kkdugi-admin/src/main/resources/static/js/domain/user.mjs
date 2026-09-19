import {localDate,validDate} from './authority.mjs';
export function userFields(row){return Object.fromEntries(['username','name','email','remarks','image','status'].map(key=>[key,String(row[key]??(key==='status'?'20':'')).trim()]));}
export function imageUrl(value,base){try{if(!value?.trim())return '';const url=new URL(value,base);return ['http:','https:'].includes(url.protocol)?url.href:'';}catch{return '';}}
export function validateUser(row,statuses=[]){const v=userFields(row);if(!v.username||!v.name||!v.email)return 'required';if(!statuses.some(status=>status.code===v.status))return 'required';if(Object.entries({username:100,name:200,email:200,remarks:1000,image:500}).some(([key,max])=>v[key].length>max))return 'user_length';if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email))return 'user_email_invalid';if(v.image&&!imageUrl(v.image,'http://localhost/'))return 'user_image_invalid';return '';}
export function authorityItems(rows,{assigned=false,canWrite=false,canDelete=false}={}){return rows.map(row=>({...row,name:row.name||row.id,subtitle:[row.role,row.type,row.use==='N'?'N':null].filter(Boolean).join(' · '),assigned,selected:assigned,selectionLocked:assigned?!canDelete:!canWrite,datesLocked:!canWrite,applyStartDate:row.applyStartDate||localDate(),applyEndDate:row.applyEndDate||'9999-12-31'}));}
export function authorityChanges(before,items,{canWrite=false,canDelete=false}={}){
 const fail=key=>{throw Object.assign(new Error(key),{validationKey:key});};
 const selected=items.filter(item=>item.selected),ids=new Set();
 for(const item of selected){if(ids.has(item.id))fail('required');ids.add(item.id);if(!validDate(item.applyStartDate)||!validDate(item.applyEndDate))fail('authority_dates_required');if(item.applyEndDate<item.applyStartDate)fail('invalid_date');}
 const old=new Map(before.map(item=>[item.id,item])),result={insert:[],update:[],delete:[]};
 for(const item of selected){const value={id:item.id,applyStartDate:item.applyStartDate,applyEndDate:item.applyEndDate},previous=old.get(item.id);if(!previous)result.insert.push(value);else if(previous.applyStartDate!==value.applyStartDate||previous.applyEndDate!==value.applyEndDate)result.update.push(value);}
 result.delete=before.filter(item=>!ids.has(item.id)).map(({id})=>({id}));
 if((result.insert.length||result.update.length)&&!canWrite||result.delete.length&&!canDelete)fail('forbidden');
 return result;
}
