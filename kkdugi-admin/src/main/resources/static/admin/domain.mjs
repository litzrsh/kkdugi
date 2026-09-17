export const clone=v=>JSON.parse(JSON.stringify(v));
export const screenDefs={
 admcode:{resource:'code',icon:'las la-layer-group',desc:'code_desc',batch:true,filters:['path','code','name','use']},
 admmsge:{resource:'i18n',icon:'las la-language',desc:'message_desc',batch:true,filters:['code','message']},
 admmenu:{resource:'menu',icon:'las la-sitemap',desc:'menu_desc',batch:true,filters:['name']},
 admauth:{resource:'authority',icon:'las la-user-shield',desc:'auth_desc',batch:false,filters:['role','type','name']},
 aduser:{resource:'user',icon:'las la-users',desc:'user_desc',batch:false,filters:['username','name','status']}
};
export function flatten(nodes,depth=0,parentId=''){return nodes.flatMap(node=>{const {children=[],...rest}=node;return [{...rest,_depth:depth,_parentId:parentId},...flatten(children,depth+1,node.id)];});}
export function rowKey(row){return row._key||row.id||row.code;}
export function cleanRow(row){return Object.fromEntries(Object.entries(clone(row)).filter(([k])=>!k.startsWith('_')&&k!=='children'));}
export function payload(rows,baseline){const out={insert:[],update:[],delete:[]};for(const row of rows){const orig=baseline.find(x=>rowKey(x)===rowKey(row));if(!orig){if(!row._deleted)out.insert.push({...cleanRow(row),...(row.code&& !('id'in row)?{}:{id:''})});}else if(row._deleted){out.delete.push(cleanRow(orig));}else if(JSON.stringify(cleanRow(row))!==JSON.stringify(cleanRow(orig))){out.update.push(cleanRow(row));}}return out;}
export function rowState(row,baseline){const p=payload([row],baseline);return p.insert.length?'new':p.delete.length?'removed':p.update.length?'changed':'';}
export function localeValue(row,locale,field='name'){const val=row.locale?.[locale];return typeof val==='string'?val:val?.[field]||'';}
export function isProtected(row,rows=[]){return Object.keys(screenDefs).includes(row.program)||rows.some(r=>Object.keys(screenDefs).includes(r.program)&&r.path?.startsWith((row.path||'~')+'/'));}
export function validate(resource,row,languages){if(resource==='i18n'){if(!/^[a-z0-9]+(_[a-z0-9]+)*(\.[a-z0-9]+(_[a-z0-9]+)*){2}$/.test(row.code||'')||/[\r\n]/.test(row.code||''))return 'invalid_code';if(!Object.values(row.locale||{}).some(v=>typeof v==='string'&&v.trim()))return 'required';}
 if(resource==='code'&&(!row.code?.trim()||!Object.values(row.locale||{}).some(v=>v.name?.trim())))return 'required';
 if(resource==='menu'&&!Object.values(row.locale||{}).some(v=>v.label?.trim()))return 'required';
 if(resource==='authority'&&(!row.role?.trim()||!row.name?.trim()||!row.type?.trim()))return 'required';
 if(resource==='user'&&(!row.username?.trim()||!row.name?.trim()||!row.status))return 'required';
 if(resource==='user'&&row.email&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(row.email))return 'invalid_email';
 if(['code','menu'].includes(resource)&&(!Number.isInteger(Number(row.sort))||Number(row.sort)<0))return 'invalid_sort';return '';}
export function assignmentDiff(before,after){return {insert:after.filter(x=>!before.some(b=>b.id===x.id)),update:after.filter(x=>before.some(b=>b.id===x.id&&JSON.stringify(b)!==JSON.stringify(x))),delete:before.filter(x=>!after.some(a=>a.id===x.id))};}
