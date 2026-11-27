import "jsr:@supabase/functions-js/edge-runtime.d.ts";
const enc = new TextEncoder();
function b64url(bytes: Uint8Array) { let s=""; for (const b of bytes) s+=String.fromCharCode(b); return btoa(s).replace(/=/g,"").replace(/\+/g,"-").replace(/\//g,"_"); }
function part(value: unknown) { return b64url(enc.encode(JSON.stringify(value))); }
function pemBytes(pem: string) { const raw=atob(pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,"")); return Uint8Array.from(raw,c=>c.charCodeAt(0)); }
async function googleToken(sa: Record<string,string>) {
 const now=Math.floor(Date.now()/1000);
 const unsigned=part({alg:"RS256",typ:"JWT"})+"."+part({iss:sa.client_email,scope:"https://www.googleapis.com/auth/firebase.messaging",aud:sa.token_uri||"https://oauth2.googleapis.com/token",iat:now,exp:now+3600});
 const key=await crypto.subtle.importKey("pkcs8",pemBytes(sa.private_key),{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]);
 const sig=new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5",key,enc.encode(unsigned)));
 const res=await fetch(sa.token_uri||"https://oauth2.googleapis.com/token",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:new URLSearchParams({grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer",assertion:unsigned+"."+b64url(sig)})});
 const data=await res.json(); if(!res.ok||!data.access_token) throw new Error("Google OAuth token exchange failed"); return data.access_token as string;
}
Deno.serve(async req=>{
 if(req.method!=="POST") return new Response("Method not allowed",{status:405});
 const expected=Deno.env.get("FREELANCE_HUB_PUSH_WEBHOOK_SECRET")||"";
 if(!expected||req.headers.get("x-push-webhook-secret")!==expected) return new Response("Unauthorized",{status:401});
 try {
  const payload=await req.json(); const row=payload.record??payload; const userId=String(row.user_id||"");
  if(!/^[0-9a-f-]{36}$/i.test(userId)) return Response.json({error:"Invalid user id"},{status:400});
  const url=Deno.env.get("SUPABASE_URL")||""; const key=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")||""; const saText=Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON")||"";
  if(!url||!key||!saText) return Response.json({error:"Push secrets are not configured"},{status:503});
  const sa=JSON.parse(saText); const query=new URL("/rest/v1/device_push_tokens",url);
  query.searchParams.set("select","id,fcm_token"); query.searchParams.set("user_id","eq."+userId); query.searchParams.set("is_active","eq.true");
  const list=await fetch(query,{headers:{apikey:key,Authorization:"Bearer "+key}}); if(!list.ok) throw new Error("Token lookup failed");
  const devices=await list.json() as Array<{id:string;fcm_token:string}>; if(!devices.length) return Response.json({sent:0,reason:"no_active_devices"});
  const access=await googleToken(sa); const title=String(row.title||"Freelance Hub").slice(0,120); const body=String(row.body||"You have a new notification.").slice(0,1000);
  const sent=await Promise.all(devices.map(async device=>{
   const response=await fetch("https://fcm.googleapis.com/v1/projects/"+encodeURIComponent(sa.project_id)+"/messages:send",{method:"POST",headers:{Authorization:"Bearer "+access,"Content-Type":"application/json"},body:JSON.stringify({message:{token:device.fcm_token,notification:{title,body},data:{notification_id:String(row.id||""),screen:String(row.type||"notifications"),resource_id:String(row.resource_id||"")},android:{priority:"HIGH",notification:{channel_id:"freelance_hub_updates"}}}})});
   if(!response.ok){const error=await response.text();if(response.status===404||error.includes("UNREGISTERED")) await fetch(new URL("/rest/v1/device_push_tokens?id=eq."+encodeURIComponent(device.id),url),{method:"PATCH",headers:{apikey:key,Authorization:"Bearer "+key,"Content-Type":"application/json"},body:JSON.stringify({is_active:false})});}
   return response.ok;
  }));
  return Response.json({sent:sent.filter(Boolean).length,total:devices.length});
 } catch { return Response.json({error:"Push delivery failed; inspect function logs."},{status:500}); }
});