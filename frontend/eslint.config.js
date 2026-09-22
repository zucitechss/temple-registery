import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tsParser from '@typescript-eslint/parser'
import tsPlugin from '@typescript-eslint/eslint-plugin'

export default [
  {
    ignores: ['dist/**', 'coverage/**'],
  },
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2020,
      sourceType: 'module',
      parser: tsParser,
      parserOptions: {
        ecmaFeatures: {
          jsx: true,
        },
      },
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...js.configs.recommended.rules,
      ...tsPlugin.configs.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      'no-undef': 'off',
      // The codebase uses the `const X = {...} as const` + `type X = (typeof X)[keyof
      // typeof X]` enum idiom, where a value and a type deliberately share a name. Both
      // the base rule and its TypeScript-aware version report that as a redeclaration —
      // ignoreDeclarationMerge only covers interface/namespace merging, not a value
      // paired with a type alias. It was the only thing stopping lint from gating CI.
      //
      // Turning it off loses nothing: `tsc --noEmit` runs in the same CI job and reports
      // a genuine redeclaration as TS2451, with the type checker's full view.
      'no-redeclare': 'off',
      '@typescript-eslint/no-redeclare': 'off',
      'react-refresh/only-export-components': 'off',
      'react-hooks/exhaustive-deps': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': 'off',
      '@typescript-eslint/no-unused-expressions': 'off',
      '@typescript-eslint/no-non-null-asserted-optional-chain': 'off',
      '@typescript-eslint/no-require-imports': 'off',
      'no-empty': 'off',
    },
  },
]
