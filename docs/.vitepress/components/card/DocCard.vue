<script setup>
defineProps({
  title: String,
  icon: String,
  link: String,
  desc: String
})
</script>

<template>
  <a :href="link" class="doc-card">
    <div class="card-icon">{{ icon }}</div>
    <div class="card-content">
      <h3 class="card-title">{{ title }}</h3>
      <p class="card-desc">{{ desc }}</p>
    </div>
  </a>
</template>

<style scoped>
.doc-card {
  display: flex;
  flex-direction: column;
  padding: 24px;
  background-color: var(--vp-c-bg-soft);
  border: 1px solid var(--vp-c-border);
  border-radius: 12px;
  text-decoration: none !important;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.03);
  transition: border-color 0.3s cubic-bezier(0.25, 0.8, 0.25, 1),
              background-color 0.3s cubic-bezier(0.25, 0.8, 0.25, 1),
              transform 0.3s cubic-bezier(0.25, 0.8, 0.25, 1),
              box-shadow 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}

.doc-card:hover {
  border-color: var(--vp-c-brand-1);
  background: linear-gradient(135deg, var(--vp-c-bg-soft), var(--vp-c-bg-mute));
  transform: translateY(-4px);
  box-shadow: 0 12px 24px rgba(0, 0, 0, 0.08), 
              0 0 0 1px color-mix(in srgb, var(--vp-c-brand-1) 15%, transparent);
}

.card-icon {
  font-size: 2.5rem;
  margin-bottom: 16px;
  transition: transform 0.3s cubic-bezier(0.175, 0.885, 0.32, 1.275);
  display: inline-block;
  width: max-content;
}

.doc-card:hover .card-icon {
  transform: scale(1.15) rotate(5deg);
}

.card-title {
  margin: 0 !important;
  font-size: 1.15rem;
  font-weight: 600;
  color: var(--vp-c-text-1);
  transition: color 0.2s;
}

.doc-card:hover .card-title {
  color: var(--vp-c-brand-1);
}

.card-desc {
  margin: 8px 0 0 0 !important;
  font-size: 0.9rem;
  color: var(--vp-c-text-2);
  line-height: 1.5;
}
</style>
