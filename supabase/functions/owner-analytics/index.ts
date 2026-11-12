import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const ORIGIN = "https://sajibu730-jpg.github.io";
const headers = { "Access-Control-Allow-Origin": ORIGIN, "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS", "Vary": "Origin" };
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...headers, "Content-Type": "application/json" } });
const countStatuses = (rows: Array<{status?: string|null}>) => rows.reduce((acc: Record<string,number>, row) => { const key = row.status || "unspecified"; acc[key] = (acc[key] || 0) + 1; return acc; }, {});
const countMatching = (rows: Array<{status?:string|null}>, pattern:RegExp) => rows.filter(row=>pattern.test(String(row.status||"").toLowerCase())).length;
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

    const readAll=async(table:string,columns:string)=>{
      const output:any[]=[]; let offset=0;
      while(offset<1000000){
        const {data,error}=await admin.from(table).select(columns).range(offset,offset+999);
        if(error) throw error;
        const batch=data||[]; output.push(...batch);
        if(batch.length<1000) break;
        offset+=1000;
      }
      return output;
    };
    const [profiles,jobs,proposals,contracts,submissions,activity,messagesR,conversationsR] = await Promise.all([
      readAll("profiles","id,country,created_at"),
      readAll("jobs","status,created_at"),
      readAll("proposals","status,created_at"),
      readAll("contracts","status,created_at"),
      readAll("work_submissions","status,submitted_at,created_at"),
      readAll("user_platform_activity","user_id,platform,last_seen_at"),
      admin.from("messages").select("id",{count:"exact",head:true}),
      admin.from("conversations").select("id",{count:"exact",head:true})
    ]);
    if(messagesR.error) throw messagesR.error;
    if(conversationsR.error) throw conversationsR.error;

    const authUsers:any[]=[]; let page=1;
    while(page<=1000){
      const {data,error}=await admin.auth.admin.listUsers({page,perPage:1000});
      if(error) throw error;
      const batch=data.users||[]; authUsers.push(...batch);
      if(batch.length<1000) break;
      page++;
    }
    const now=Date.now(),day=86400000,since=(n:number)=>now-n*day;
    const new7=authUsers.filter(u=>u.created_at&&new Date(u.created_at).getTime()>=since(7)).length;
    const new30=authUsers.filter(u=>u.created_at&&new Date(u.created_at).getTime()>=since(30)).length;
    const uniqueActive=(days:number)=>new Set(activity.filter((x:any)=>new Date(x.last_seen_at).getTime()>=since(days)).map((x:any)=>x.user_id)).size;
    const platformCounts:Record<string,number>={};
    for(const row of activity as any[]){if(new Date(row.last_seen_at).getTime()>=since(30))platformCounts[row.platform]=(platformCounts[row.platform]||0)+1;}
    const countryCounts:Record<string,number>={},roleCounts:Record<string,number>={};
    for(const p of profiles as any[]){const country=String(p.country||"Not set").trim()||"Not set";countryCounts[country]=(countryCounts[country]||0)+1;}
    for(const u of authUsers as any[]){const role=String(u.user_metadata?.role||"Not set").trim().toLowerCase()||"Not set";roleCounts[role]=(roleCounts[role]||0)+1;}
    return reply({
      success:true,generated_at:new Date().toISOString(),
      users:{total:authUsers.length,confirmed:authUsers.filter(u=>!!u.email_confirmed_at).length,new_7d:new7,new_30d:new30,profiles:profiles.length,missing_profiles:Math.max(0,authUsers.length-profiles.length),active_24h:uniqueActive(1),active_7d:uniqueActive(7),active_30d:uniqueActive(30),roles:roleCounts,countries:Object.entries(countryCounts).sort((a,b)=>b[1]-a[1]).slice(0,12).map(([country,count])=>({country,count}))},
      platforms_30d:platformCounts,
      jobs:{total:jobs.length,open:countMatching(jobs,/open|published/),ongoing:countMatching(jobs,/in.?progress|ongoing|active/),completed:countMatching(jobs,/complete|done/),cancelled:countMatching(jobs,/cancel|closed/),statuses:countStatuses(jobs)},
      proposals:{total:proposals.length,pending:countMatching(proposals,/pending|submitted|review/),accepted:countMatching(proposals,/accept/),rejected:countMatching(proposals,/reject/),statuses:countStatuses(proposals)},
      contracts:{total:contracts.length,active:countMatching(contracts,/active|ongoing|in.?progress/),completed:countMatching(contracts,/complete|done/),cancelled:countMatching(contracts,/cancel/),statuses:countStatuses(contracts)},
      work:{submissions:submissions.length,pending_review:countMatching(submissions,/submit|pending|review|queued/),approved:countMatching(submissions,/approv|complete/),statuses:countStatuses(submissions)},
      communication:{messages:messagesR.count||0,conversations:conversationsR.count||0},
      coverage:{active_users:"Active means a signed-in account recorded activity in the selected period. Only activity recorded after tracking is installed is available.",platforms:"Android/web/iOS clients call track-platform-activity. Active-user totals deduplicate by account ID across platforms.",revenue:"Not included until a verified payment ledger is connected."}
    });
  }catch(error){
    console.error("owner-analytics failed",error);
    return reply({success:false,error:"Analytics could not be loaded. Check the Owner Dashboard function logs."},500);
  }
});