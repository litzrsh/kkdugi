import vm from 'node:vm';
import fs from 'node:fs/promises';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'../src/main/resources/static');
const context=vm.createContext({console,TextEncoder,TextDecoder,URL,URLSearchParams,setTimeout,clearTimeout,atob,btoa});context.window=context;context.self=context;context.globalThis=context;
vm.runInContext(await fs.readFile(path.join(root,'vendor/vue.global.prod.js'),'utf8'),context);
vm.runInContext(await fs.readFile(path.join(root,'vendor/vue3-sfc-loader.js'),'utf8'),context);
const options={moduleCache:{vue:context.Vue},getFile:async url=>fs.readFile(path.join(root,url),'utf8'),addStyle:()=>{throw Error('Unexpected component CSS');}};
for(const file of ['admin/App.vue','admin/DialogHost.vue','admin/Grid.vue']){const component=await context['vue3-sfc-loader'].loadModule('/'+file,options);if(!component.render&&!component.setup)throw Error('Missing render/setup: '+file);console.log('Compiled '+file);}
