<template>
 <CodePage th:if="${authorities['10']}" ref="page" :permissions="permissions" :menu-id="menuId" :title="title" :remarks="remarks"/>
 <p th:unless="${authorities['10']}" role="alert" th:text="#{admin.ui.forbidden}">접근할 수 없습니다.</p>
</template>
<script setup th:inline="javascript">
import {ref} from 'vue';
import CodePage from '@vue/pages/CodePage.vue';
defineProps({menuId:String,title:String,remarks:String,locale:String});
const permissions = /*[[${authorities}]]*/ {};
const page=ref(null);
defineExpose({beforeLeave:reason=>page.value?.beforeLeave(reason)??Promise.resolve(true)});
</script>
