package com.ruoyi.club.service;

/** One admission decision, shared by product binding and order acceptance. */
final class ClubPlayerEligibility {
    private ClubPlayerEligibility() { }
    static String approved(String profileAlias) {
        String user=profileAlias+".user_id";
        return "exists(select 1 from club_user u where u.id="+user+" and u.status='active' and u.user_type='player')"
            +" and (exists(select 1 from club_application a where a.user_id="+user+" and a.application_type='player' and a.status='approved'"
            +" and a.id=(select max(a2.id) from club_application a2 where a2.user_id="+user+" and a2.application_type='player'))"
            +" or (not exists(select 1 from club_application a where a.user_id="+user+" and a.application_type='player')"
            +" and exists(select 1 from club_identity i where i.user_id="+user+" and i.status='approved')))"
            +" and not exists(select 1 from club_identity i where i.user_id="+user+" and i.status<>'approved')";
    }
    static String activeSkus(String profileAlias) {
        return "select sk.id from club_product pr join club_product_sku sk on sk.product_id=pr.id and sk.status='active'"
            +" where pr.bound_player_id="+profileAlias+".id and pr.status='active'";
    }
}
