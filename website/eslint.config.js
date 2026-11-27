import js from '@eslint/js'
import { fixupPluginRules } from '@eslint/compat'
import prettier from 'eslint-config-prettier'
import { defineConfig, globalIgnores } from 'eslint/config'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default defineConfig(
  globalIgnores([
    'dist',
    'coverage',
    'playwright-report',
    'blob-report',
    'test-results',
    '.tanstack',
    '.output',
    'src/routeTree.gen.ts',
  ]),
  js.configs.recommended,
  tseslint.configs.recommendedTypeChecked,
  tseslint.configs.stylisticTypeChecked,
  {
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
      globals: { ...globals.browser },
    },
    rules: {
      '@typescript-eslint/consistent-type-imports': ['error', { fixStyle: 'inline-type-imports' }],
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      'no-console': ['error', { allow: ['warn', 'error'] }],
      eqeqeq: ['error', 'always'],
      // Object shapes are written as type aliases throughout; either form is fine.
      '@typescript-eslint/consistent-type-definitions': 'off',
    },
  },
  {
    files: ['src/**/*.tsx'],
    extends: [reactHooks.configs.flat['recommended-latest']],
  },
  {
    // jsx-a11y predates ESLint 10; compat shims the context APIs it still calls.
    files: ['src/**/*.tsx'],
    ...jsxA11y.flatConfigs.recommended,
    plugins: { 'jsx-a11y': fixupPluginRules(jsxA11y) },
  },
  {
    files: ['*.{js,ts}', 'scripts/**', 'e2e/**'],
    languageOptions: { globals: { ...globals.node } },
  },
  {
    files: ['**/*.js'],
    extends: [tseslint.configs.disableTypeChecked],
  },
  // Last: turn off every stylistic rule Prettier owns.
  prettier,
)
