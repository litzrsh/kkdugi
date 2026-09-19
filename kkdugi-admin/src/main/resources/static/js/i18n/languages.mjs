// extra1 is the locale identity; the common code value is not a locale.
export function languageOptions(rows){
 const options=new Map();
 for(const row of rows){const code=String(row.extra1??'').trim();if(!/^[A-Za-z]{2,8}(?:[_-][A-Za-z0-9]{1,8})*$/.test(code)||options.has(code))continue;options.set(code,{code,label:row.name?.trim()||code});}
 return [...options.values()];
}
