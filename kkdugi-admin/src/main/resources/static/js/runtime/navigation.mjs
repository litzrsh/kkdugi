export function normalizeMenus(nodes){
 if(!Array.isArray(nodes))throw new Error('Invalid menu response');const seen=new Set();
 function walk(items){return items.map(item=>{if(typeof item.id!=='string'||seen.has(item.id))throw new Error('Invalid menu identity');seen.add(item.id);return {...item,children:walk(item.children??[])};}).sort((a,b)=>(a.sort??0)-(b.sort??0));}
 return walk(nodes);
}
export function findMenu(nodes,id){for(const node of nodes){if(node.id===id)return node;const found=findMenu(node.children,id);if(found)return found;}return null;}
export function menuHash(id){return id?'#menu='+encodeURIComponent(id):'';}
export function menuIdFromHash(hash){if(!hash)return '';if(!hash.startsWith('#menu='))return null;try{return decodeURIComponent(hash.slice(6));}catch{return null;}}
export function menuIcon(value){const named={settings:'cog',code:'code',language:'language',users:'users',shield:'shield-alt'};if(named[value])return 'las la-'+named[value];return /^las la-[a-z0-9-]+$/.test(value||'')?value:'las la-folder';}
export function createPageRequests(load){
 let version=0,controller;
 return {cancel(){version++;controller?.abort();},async open(id){const ticket=++version;controller?.abort();controller=new AbortController();try{const component=await load(id,controller.signal);return ticket===version?{component}:null;}catch(error){if(ticket!==version||error.name==='AbortError')return null;throw error;}}};
}
