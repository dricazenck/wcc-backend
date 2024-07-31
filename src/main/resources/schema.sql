DEFINE TABLE page SCHEMAFULL;

DEFINE FIELD uuid ON page TYPE string ASSERT $value != NONE;
DEFINE FIELD path ON page TYPE string ASSERT $value != NONE;
DEFINE FIELD content ON page TYPE blob ASSERT $value != NONE;