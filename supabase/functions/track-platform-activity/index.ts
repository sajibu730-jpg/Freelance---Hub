import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const headers = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const reply=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{"Content-Type":"application/json",...headers}});
const platforms=new Set(["android","web","ios","other"]);
const events=new Set(["session_start","app_open","login","job_view","job_post","proposal_submit","message_send","contract_update","work_submit"]);
Deno.serve(async(req:Request)=>{
  if(req.method==="OPTIONS") return new Response("ok",{headers});
  if(req.method!=="POST") return reply({success:false,error:"Method not allowed."},405);
  try{
    const url=Deno.env.get("SUPABASE_URL"),anon=Deno.env.get("SUPABASE_ANON_KEY"),service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if(!url||!anon||!service) return reply({success:false,error:"Server configuration is incomplete."},503);
    const token=(req.headers.get("Authorization")||"").match(/^Bearer\\s+(.+)$/i)?.[1];
    if(!token) return reply({success:false,error:"Sign in required."},401);
    const client=createClient(url,anon,{auth:{persistSession:false,autoRefreshToken:false}});
    const {data,error}=await client.auth.getUser(token);
    if(error||!data.user) return reply({success:false,error:"Invalid session."},401);
    const body=await req.json().catch(()=>({}));
    const platform=String(body.platform||"").toLowerCase(), event=String(body.event||"session_start").toLowerCase();
    if(!platforms.has(platform)||!events.has(event)) return reply({success:false,error:"Unsupported platform or event."},400);
    const admin=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}});
    const {error:writeError}=await admin.from("user_platform_activity").upsert({user_id:data.user.id,platform,last_seen_at:new Date().toISOString(),last_activity_type:event},{onConflict:"user_id,platform"});
    if(writeError) throw writeError;
    return reply({success:true,recorded:true});
  }catch(error){
    console.error("track-platform-activity failed",error);
    return reply({success:false,error:"Activity could not be recorded."},500);
  }
});