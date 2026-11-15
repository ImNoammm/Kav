import rtlcss from 'postcss-rtlcss'

// VitePress 1.6 still uses physical directions in its default theme.
export default { plugins: [rtlcss({ safeBothPrefix: true, processKeyFrames: true })] }
