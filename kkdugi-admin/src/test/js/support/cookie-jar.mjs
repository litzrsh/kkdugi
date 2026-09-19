// Minimal document.cookie stand-in for Node tests: the token now lives in the KKDUGI_TOKEN cookie, not sessionStorage.
const jar=new Map();
globalThis.document={
 get cookie(){return [...jar].map(([k,v])=>k+'='+v).join('; ');},
 set cookie(line){const [pair,...attrs]=line.split(';');const i=pair.indexOf('=');const name=pair.slice(0,i).trim(),value=pair.slice(i+1);
  if(attrs.some(a=>a.trim()==='Max-Age=0'))jar.delete(name);else jar.set(name,value);}
};
export const setTokenCookie=token=>{document.cookie='KKDUGI_TOKEN='+token;};
