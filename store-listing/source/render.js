const {chromium}=require('playwright');(async()=>{const b=await chromium.launch();
const jobs=process.argv.slice(2);for(const j of jobs){const [n,w,h]=j.split(':');const p=await b.newPage({viewport:{width:+w,height:+h}});
await p.goto('file://'+__dirname+'/html/'+n+'.html');await p.waitForTimeout(400);await p.screenshot({path:__dirname+'/out/'+n+'.png'});await p.close();}
await b.close();})();
