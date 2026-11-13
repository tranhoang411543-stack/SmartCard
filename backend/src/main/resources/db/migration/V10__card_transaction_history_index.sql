CREATE INDEX wallet_applied_by_card ON wallet_operations(card_id, id DESC) WHERE status='APPLIED';
