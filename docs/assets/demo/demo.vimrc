" The demo tape's vim: syntax colors on the terminal's own dark background, line numbers, no
" swap files or plugins.
set nocompatible
set background=dark
syntax on
colorscheme habamax
highlight Normal ctermbg=NONE guibg=NONE
highlight LineNr ctermbg=NONE guibg=NONE
set number
set noswapfile
set laststatus=0
set shortmess+=I
set tabstop=4
