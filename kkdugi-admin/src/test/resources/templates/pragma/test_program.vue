<template>
  <div class="pragma-test-fixture">
    <span th:if="${authorities['10']}" id="read-flag">READ_OK</span>
    <span th:if="${authorities['20']}" id="wrte-flag">WRTE_OK</span>
  </div>
</template>
<script setup>
</script>
