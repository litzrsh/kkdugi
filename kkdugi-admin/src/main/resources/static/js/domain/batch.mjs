export const clone=v=>JSON.parse(JSON.stringify(v));
export const screenDefs={
 admcode:{resource:'code',icon:'las la-layer-group',desc:'code_desc',batch:true,filters:['path','code','name','use']},
 admmsge:{resource:'i18n',icon:'las la-language',desc:'message_desc',batch:true,filters:['code','message']},
 admmenu:{resource:'menu',icon:'las la-sitemap',desc:'menu_desc',batch:true,filters:['name']},
 admauth:{resource:'authority',icon:'las la-user-shield',desc:'auth_desc',batch:false,filters:['role','type','name']},
 aduser:{resource:'user',icon:'las la-users',desc:'user_desc',batch:false,filters:['username','name','status']}
};
export function flatten(nodes,depth=0,parentId=null){return (nodes??[]).flatMap(node=>{const {children,...rest}=node;return [{...rest,_depth:depth,_parentId:parentId},...flatten(children,depth+1,node.id)];});}
export function rowKey(row){return row._key||row.id||row.code;}
export function cleanRow(row){return Object.fromEntries(Object.entries(clone(row)).filter(([k])=>!k.startsWith('_')&&k!=='children'));}
export function payload(rows,baseline){const out={insert:[],update:[],delete:[]};for(const row of rows){const orig=baseline.find(x=>rowKey(x)===rowKey(row));if(!orig){if(!row._deleted)out.insert.push({...cleanRow(row),...(row.code&& !('id'in row)?{}:{id:''})});}else if(row._deleted){out.delete.push(cleanRow(orig));}else if(JSON.stringify(cleanRow(row))!==JSON.stringify(cleanRow(orig))){out.update.push(cleanRow(row));}}return out;}
export function rowState(row,baseline){const p=payload([row],baseline);return p.insert.length?'new':p.delete.length?'removed':p.update.length?'changed':'';}
export function localeValue(row,locale,field='name'){const val=row?.locale?.[locale];return typeof val==='string'?val:val?.[field]||'';}
// Menu program codes are template paths under templates/pragma/ (see V10__insert_default_menu.sql); they differ from the screenDefs keys above.
export const systemPrograms=['admin/code','admin/message','admin/menu','admin/authority','admin/user'];
export function isProtected(row,rows=[]){return systemPrograms.includes(row.program)||rows.some(r=>systemPrograms.includes(r.program)&&r.path?.startsWith((row.path||'~')+'/'));}
export function validate(resource,row,languages){if(resource==='i18n'){if(!/^[a-z0-9]+(_[a-z0-9]+)*(\.[a-z0-9]+(_[a-z0-9]+)*){2}$/.test(row.code||'')||/[\r\n]/.test(row.code||''))return 'invalid_code';if(!Object.values(row.locale||{}).some(v=>typeof v==='string'&&v.trim()))return 'required';}
 if(resource==='code'&&(!row.code?.trim()||!Object.values(row.locale||{}).some(v=>v.name?.trim())))return 'required';
 if(resource==='code'&&!row.id&&(!/^[A-Z0-9]+(_[A-Z0-9]+)*$/.test(row.code)||/[\r\n]/.test(row.code)))return 'invalid_common_code';
 if(resource==='menu'&&row.program&&(!/^[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*$/.test(row.program)||/[\r\n]/.test(row.program)))return 'invalid_program';
 if(resource==='menu'&&!Object.values(row.locale||{}).some(v=>v.label?.trim()))return 'required';
 if(resource==='authority'&&(!row.role?.trim()||!row.name?.trim()||!row.type?.trim()))return 'required';
 if(resource==='user'&&(!row.username?.trim()||!row.name?.trim()||!row.status))return 'required';
 if(resource==='user'&&row.email&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(row.email))return 'invalid_email';
 if(['code','menu'].includes(resource)&&(!Number.isInteger(Number(row.sort))||Number(row.sort)<0))return 'invalid_sort';return '';}
export function assignmentDiff(before,after){return {insert:after.filter(x=>!before.some(b=>b.id===x.id)),update:after.filter(x=>before.some(b=>b.id===x.id&&JSON.stringify(b)!==JSON.stringify(x))),delete:before.filter(x=>!after.some(a=>a.id===x.id))};}

// Optional empty translations are omitted; clearing an existing translation is not
// supported by the current API and must never be silently treated as a no-op.
export function prepareLocalizedRow(resource,row,original){
 const result=cleanRow(row),field=resource==='code'?'name':resource==='menu'?'label':null;
 result.locale={};
 for(const [language,value] of Object.entries(row.locale||{})){
  const text=field?value?.[field]:value;
  if(typeof text==='string'&&text.trim())result.locale[language]=clone(value);
  else if(original?.locale?.[language]!==undefined||(field&&value?.remarks?.trim()))throw Object.assign(new Error('required'),{validationKey:'required'});
 }
 if(!Object.keys(result.locale).length)throw Object.assign(new Error('required'),{validationKey:'required'});
 if(resource==='code'||resource==='menu')result.parentId=result.parentId||null;
 return result;
}
export function menuDescendants(rows,targets){
 const keys=new Set(targets.map(rowKey));const ids=new Set(targets.map(r=>r.id).filter(Boolean));let changed=true;
 while(changed){changed=false;for(const row of rows)if(ids.has(row.parentId)&&!keys.has(rowKey(row))){keys.add(rowKey(row));if(row.id)ids.add(row.id);changed=true;}}
 return rows.filter(row=>keys.has(rowKey(row)));
}
export function filterMenuRows(rows,query,locale){
 if(!query?.trim())return rows;const q=query.trim().toLowerCase();
 const keep=new Set(rows.filter(r=>[r.program,r.path,localeValue(r,locale,'label'),...Object.values(r.locale||{}).map(v=>v.label)].join(' ').toLowerCase().includes(q)).map(rowKey));
 for(const row of rows.filter(r=>keep.has(rowKey(r)))){let parent=rows.find(r=>r.id&&r.id===row.parentId);const seen=new Set();while(parent&&!seen.has(parent.id)){seen.add(parent.id);keep.add(rowKey(parent));parent=rows.find(r=>r.id&&r.id===parent.parentId);}}
 return rows.filter(r=>keep.has(rowKey(r)));
}
export function prepareBatch(resource,changes,baseline){
 const result={insert:changes.insert.map(r=>prepareLocalizedRow(resource,r)),update:changes.update.map(r=>prepareLocalizedRow(resource,r,baseline.find(b=>rowKey(b)===rowKey(r)))),delete:changes.delete.map(cleanRow)};
 if(resource==='menu'){
  const deleted=new Set(result.delete.map(r=>r.id));
  // Server cascades deletion; sending both parent and child would cause a 409.
  result.delete=result.delete.filter(r=>{let id=r.parentId;const seen=new Set();while(id&&!seen.has(id)){if(deleted.has(id))return false;seen.add(id);id=baseline.find(b=>b.id===id)?.parentId;}return true;});
 }
 return result;
}

export function markMenuDeleted(rows,targets,group){
 for(const row of menuDescendants(rows,targets)){row._deleted=true;row._deleteGroup=group;}
}
export function restoreMenuRows(rows,row){
 const group=row._deleteGroup;
 for(const item of rows)if(rowKey(item)===rowKey(row)||(group&&item._deleteGroup===group)){delete item._deleted;delete item._deleteGroup;}
}
export function menuLabel(row,locale){return localeValue(row,locale,'label')||Object.values(row?.locale||{}).find(v=>v?.label)?.label||row?.program||row?.id||'';}
