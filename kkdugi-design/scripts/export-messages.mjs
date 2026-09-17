import fs from 'node:fs/promises';
import path from 'node:path';
import {labels,keyOf} from '../src/main/resources/static/admin/i18n.mjs';
const dir=path.resolve(import.meta.dirname,'../src/main/resources/messages');await fs.mkdir(dir,{recursive:true});
const escape=value=>value.replaceAll('\\','\\\\').replaceAll('\n','\\n').replaceAll('\r','\\r');
for(const [locale,index] of [['ko_KR',0],['en_US',1]]){await fs.writeFile(path.join(dir,`admin-ui_${locale}.properties`),'# kkdugi admin UI. Merge into the application MessageSource bundle.\n'+Object.entries(labels).map(([key,text])=>`${keyOf(key)}=${escape(text[index])}`).join('\n')+'\n');}
console.log('Exported ko_KR/en_US Spring message bundles');
