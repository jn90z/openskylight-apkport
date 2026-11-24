import { readFileSync, writeFileSync, renameSync, mkdirSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
const root = JSON.parse(readFileSync('package.json','utf8'))
const desktopOnly = new Set(['@googleapis/calendar','better-sqlite3','drizzle-orm','electron-updater','ffmpeg-static','google-auth-library','ws','electron','electron-builder','electron-vite','playwright-core','@types/better-sqlite3','@types/ws'])
const dependencies = Object.fromEntries(Object.entries({...root.dependencies,...root.devDependencies}).filter(([name])=>!desktopOnly.has(name)))
Object.assign(dependencies, {'@capacitor/core':'7.4.3','@capacitor/cli':'7.4.3','@capacitor/android':'7.4.3'})
mkdirSync('.android-build',{recursive:true})
writeFileSync('.android-build/package.original.json',JSON.stringify(root,null,2))
writeFileSync('package.json',JSON.stringify({name:'openskylight-android',version:root.version,private:true,dependencies},null,2))
function run(command,args){const r=spawnSync(command,args,{stdio:'inherit',shell:process.platform==='win32'});if(r.status!==0)throw new Error(command+' failed: '+r.status)}
run('npm',['install','--ignore-scripts','--no-audit','--no-fund'])
run('npx',['vite','build','--config','android.vite.config.ts'])
renameSync('out/android/android.html','out/android/index.html')
run('npx',['cap','add','android'])
run('npx',['cap','sync','android'])
