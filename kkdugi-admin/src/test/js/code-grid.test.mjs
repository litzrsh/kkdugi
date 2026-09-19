import test from 'node:test';
import assert from 'node:assert/strict';
import {validate,prepareBatch} from '../../main/resources/static/js/domain/batch.mjs';

test('inline code order rejects cleared values instead of silently converting them to zero',()=>{
 const row={id:'C1',code:'CODE',locale:{en_US:{name:'Code'}},use:'Y'};
 for(const sort of [null,undefined,'',-1,1.5,NaN])assert.equal(validate('code',{...row,sort},[]),'invalid_sort');
 for(const sort of [0,9])assert.equal(validate('code',{...row,sort},[]),'');
 const request=prepareBatch('code',{insert:[],update:[{...row,sort:0}],delete:[]},[{...row,sort:1}]);
 assert.equal(request.update[0].sort,0);
});
