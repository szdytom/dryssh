vim.opt.mouse = 'a'
vim.opt.swapfile = false
local function copy(lines, regtype)
  local text = table.concat(lines, '\n')
  local encoded = vim.fn.system({'base64', '-w0'}, text)
  io.stdout:write('\27]52;c;' .. encoded .. '\7')
  io.stdout:flush()
end
local function paste() return {{}, 'v'} end
vim.g.clipboard = {
  name = 'DrySSH OSC52 acceptance',
  copy = { ['+'] = copy, ['*'] = copy },
  paste = { ['+'] = paste, ['*'] = paste },
}
