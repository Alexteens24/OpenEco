import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'OpenEco',
  description: 'Single-server-first economy plugin for Paper and Folia with optional proxy-assisted handoff',
  base: '/OpenEco/',
  cleanUrls: true,
  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/OpenEco/logo.png' }],
    ['link', { rel: 'apple-touch-icon', href: '/OpenEco/logo.png' }],
  ],
  themeConfig: {
    logo: '/logo.png',
    socialLinks: [
      { icon: 'github', link: 'https://github.com/Alexteens24/OpenEco' },
    ],
    search: {
      provider: 'local',
    },
    nav: [
      { text: 'Home', link: '/' },
      { text: 'Download', link: '/docs/download' },
      { text: 'Docs', link: '/docs/' },
    ],
    sidebar: [
      {
        text: 'General',
        items: [
          { text: 'Welcome', link: '/docs/' },
          { text: 'Features', link: '/docs/features' },
        ],
      },
      {
        text: 'Getting Started',
        items: [
          { text: 'Download', link: '/docs/download' },
          { text: 'Installation', link: '/docs/installation' },
        ],
      },
      {
        text: 'Reference',
        items: [
          { text: 'Commands', link: '/docs/commands' },
          { text: 'Permissions', link: '/docs/permissions' },
          { text: 'Configuration', link: '/docs/configuration' },
          { text: 'Placeholders', link: '/docs/placeholders' },
        ],
      },
      {
        text: 'Addons & Network',
        items: [
          { text: 'Migration', link: '/docs/migration' },
          { text: 'Production guide', link: '/docs/production' },
        ],
      },
      {
        text: 'Advanced',
        items: [
          { text: 'Addon API', link: '/docs/api' },
          { text: 'Development', link: '/docs/development' },
          { text: 'Technical notes', link: '/docs/technical' },
        ],
      },
    ],
    editLink: {
      pattern: 'https://github.com/Alexteens24/OpenEco/edit/main/docs/:path',
      text: 'Edit this page on GitHub',
    },
  },
})
