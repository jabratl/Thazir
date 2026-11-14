(function(){try{
var C=/*CFG*/null;
if(!C||!C.ad)return;
var org=location.origin,ok=false,o=C.o||[],i,p;
for(i=0;i<o.length&&!ok;i++){p=o[i];
 if(p==='*'){ok=true;break;}
 var esc=function(t){return t.replace(/[.+?^${}()|[\]\\]/g,'\\$&');};
 if(new RegExp('^'+esc(p).replace(/\\\*/g,'[^/]*')+'$').test(org)){ok=true;break;}
 if(p.indexOf('://*.')>0&&org===p.replace('://*.','://'))ok=true;
}
if(!ok)return;
var want=C.m===2;
if(!want){
 var h=String(location.hostname||'').toLowerCase().replace(/^www\./,''),r=C.r||{},v=0;
 while(h){var x=r[h];if(x===1||x===2){v=x;break;}var k=h.indexOf('.');if(k<0||h.indexOf('.',k+1)<0)break;h=h.substring(k+1);}
 want=v===1?true:v===2?false:!!C.dp;
}
if(!want)return;
var om=window.matchMedia;
if(typeof om!=='function'||typeof Proxy!=='function')return;
var re=/^\s*\(\s*prefers-color-scheme\s*:\s*(dark|light)\s*\)\s*$/i;
window.matchMedia=function(q){
 var m=om.apply(this,arguments);
 try{var t=re.exec(String(q));
  if(t){var val=t[1].toLowerCase()==='dark';
   return new Proxy(m,{get:function(tg,pr){if(pr==='matches')return val;var f=tg[pr];return typeof f==='function'?f.bind(tg):f;}});}
 }catch(e){}
 return m;
};
}catch(e){}})();
