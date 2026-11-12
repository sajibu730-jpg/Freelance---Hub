import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const ORIGIN = "https://sajibu730-jpg.github.io";
const headers = { "Access-Control-Allow-Origin": ORIGIN, "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS", "Vary": "Origin" };
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...headers, "Content-Type": "application/json" } });
const countStatuses = (rows: Array<{status?: string|null}>) => rows.reduce((acc: Record<string,number>, row) => { const key = row.status || "unspecified"; acc[key] = (acc[key] || 0) + 1; return acc; }, {});
Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return reply({success:false,error:"Method not allowed."},405);
  try {
    const url=Deno.env.get("SUPABASE_URL"), anon=Deno.env.get("SUPABASE_ANON_KEY"), service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if(!url||!anon||!service) return reply({success:false,error:"Server configuration is incomplete."},503);
    const token=(req.headers.get("Authorization")||"").match(/^Bearer\s+(.+)$/i)?.[1];
    if(!token) return reply({success:false,error:"Owner sign-in required."},401);
    const authClient=createClient(url,anon,{auth:{persistSession:false,autoRefreshToken:false}});
    const {data:userData,error:userError}=await authClient.auth.getUser(token);
    if(userError||!userData.user) return reply({success:false,error:"Session invalid or expired."},401);
    const admin=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}});
    const {data:owner,error:ownerError}=await admin.from("app_admins").select("role,is_active").eq("user_id",userData.user.id).maybeSingle();
    if(ownerError) throw ownerError;
    if(owner?.role!=="owner"||owner?.is_active!==true) return reply({success:false,error:"Only the primary active Owner can view platform analytics."},403);

    const [profilesR,jobsR,proposalsR,contractsR,submissionsR,messagesR,conversationsR,activityR] = await Promise.all([
      admin.from("profiles").select("id,country,capabilities,created_at"),
      admin.from("jobs").select("status,created_at"),
      admin.from("proposals").select("status,created_at"),
      admin.from("contracts").select("status,created_at"),
      admin.from("work_submissions").select("status,submitted_at,created_at"),
      admin.from("messages").select("id",{count:"exact",head:true}),
      admin.from("conversations").select("id",{count:"exact",head:true}),
      admin.from("user_platform_activity").select("user_id,platform,last_seen_at")
    ]);
    for(const result of [profilesR,jobsR,proposalsR,contractsR,submissionsR,messagesR,conversationsR,activityR]) if(result.error) throw result.error;

    const authUsers:any[]=[]; let page=1;
    while(page<=100){
      const {data,error}=await admin.auth.admin.listUsers({page,perPage:1000});
      if(error) throw error;
      const batch=data.users||[]; authUsers.push(...batch);
      if(batch.length<1000) break;
      page++;
    }
    const now=Date.now(), day=86400000, since=(n:number)=>now-n*day;
    const new7=authUsers.filter(u=>u.created_at&&new Date(u.created_at).getTime()>=since(7)).length;
    const new30=authUsers.filter(u=>u.created_at&&new Date(u.created_at).getTime()>=since(30)).length;
    const uniqueActive=(days:number)=>new Set((activityR.data||[]).filter((x:any)=>new Date(x.last_seen_at).getTime()>=since(days)).map((x:any)=>x.user_id)).size;
    const platformCounts:Record<string,number>={};
    for(const row of (activityR.data||[]) as any[]){if(new Date(row.last_seen_at).getTime()>=since(30))platformCounts[row.platform]=(platformCounts[row.platform]||0)+1;}
    const countryCounts:Record<string,number>={}, roleCounts:Record<string,number>={};
    for(const p of (profilesR.data||[]) as any[]){
      const country=String(p.country||"Not set").trim()||"Not set";countryCounts[country]=(countryCounts[country]||0)+1;
      const role=String(p.capabilities||"Not set").trim().toLowerCase()||"Not set";roleCounts[role]=(roleCounts[role]||0)+1;
    }
    const jobs=jobsR.data||[],proposals=proposalsR.data||[],contracts=contractsR.data||[],submissions=submissionsR.data||[];
    return reply({success:true,generated_at:new Date().toISOString(),users:{total:authUsers.length,confirmed:authUsers.filter(u=>!!u.email_confirmed_at).length,new_7d:new7,new_30d:new30,profiles:profilesR.data?.length||0,missing_profiles:Math.max(0,authUsers.length-(profilesR.data?.length||0)),active_24h:uniqueActive(1),active_7d:uniqueActive(7),active_30d:uniqueActive(30),roles:roleCounts,countries:Object.entries(countryCounts).sort((a,b)=>b[1]-a[1]).slice(0,12).map(([country,count])=>({country,count}))},platforms_30d:platformCounts,jobs:{total:jobs.length,statuses:countStatuses(jobs)},proposals:{total:proposals.length,statuses:countStatuses(proposals)},contracts:{total:contracts.length,statuses:countStatuses(contracts)},work:{submissions:submissions.length,statuses:countStatuses(submissions)},communication:{messages:messagesR.count||0,conversations:conversationsR.count||0},coverage:{active_users:"Only authenticated activity recorded after platform tracking is installed; older activity is unavailable.",platforms:"Android/web/iOS clients must call track-platform-activity. Counts are unique accounts across platforms for active-user totals.",revenue:"Not included until a verified payment/ledger source is connected."}});
  }catch(error){
    console.error("owner-analytics failed",error);
    return reply({success:false,error:"Analytics could not be loaded. Check the Owner Dashboard function logs."},500);
  }
});