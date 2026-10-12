fx_version 'cerulean'
lua54 'yes'
game 'gta5'
games { 'gta5', 'rdr3' }
rdr3_warning 'I acknowledge that this is a prerelease build of RedM, and I am aware my resources *will* become incompatible once RedM ships.'
node_version '22'

name 'playermanager_bridge'
description 'Player Manager Bridge: live player data for the Calagopus Player Manager panel'
author 'Player Manager Bridge'
version '1.0.0'

server_scripts {
    'server/main.js',
}

client_scripts {
    'client/client.lua',
}
