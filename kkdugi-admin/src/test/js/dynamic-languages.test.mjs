import test from 'node:test';import assert from 'node:assert/strict';
import {languageOptions} from '../../main/resources/static/js/i18n/languages.mjs';
import {createApi} from '../../main/resources/static/js/api/index.mjs';
import {prepareLocalizedRow} from '../../main/resources/static/js/domain/batch.mjs';
test('available languages use extra1, not the common code identity',()=>{
 const options=languageOptions([{code:'KOREAN',extra1:' ko_KR ',name:'한국어'},{code:'JAPANESE',extra1:'ja_JP',name:'日本語'},{extra1:'en_US',name:'English'},{extra1:'ja_JP',name:'duplicate'},{extra1:'',name:'empty'},{extra1:'__proto__',name:'invalid'},{extra1:'zh_Hant_TW'}]);
 assert.deepEqual(options,[{code:'ko_KR',label:'한국어'},{code:'ja_JP',label:'日本語'},{code:'en_US',label:'English'},{code:'zh_Hant_TW',label:'zh_Hant_TW'}]);assert.deepEqual(languageOptions([]),[]);
});
test('language child request retains locale, origin menu, cancellation and exact-path defaults',async()=>{
 const calls=[],controller=new AbortController(),api=createApi({},async(url,options)=>{calls.push({url,...options});return Response.json([]);}).forMenu('M_MESSAGES');
 await api.codes('/SYS/LANG',{children:true,locale:'ko_KR',signal:controller.signal});
 assert.equal(calls[0].url,'/api/v1.0/code?path=%2FSYS%2FLANG&children=true&lang=ko_KR');assert.equal(calls[0].headers['X-Menu-Id'],'M_MESSAGES');assert.equal(calls[0].signal,controller.signal);
 await api.codes('/SYS/LANG');assert.equal(calls[1].url,'/api/v1.0/code?path=%2FSYS%2FLANG');
});
test('inactive translations survive edits to active languages',()=>{
 const old={id:'C',code:'TEST',locale:{ko_KR:{name:'기존'},en_US:{name:'Keep'}}};
 const result=prepareLocalizedRow('code',{...old,locale:{...old.locale,ko_KR:{name:'변경'}}},old);
 assert.equal(result.locale.en_US.name,'Keep');
 const message=prepareLocalizedRow('i18n',{code:'test.msg.label',locale:{ko_KR:'변경',en_US:'Keep'}},{code:'test.msg.label',locale:{ko_KR:'기존',en_US:'Keep'}});assert.equal(message.locale.en_US,'Keep');
});
