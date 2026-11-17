export interface KeycloakTokenClaims {
  readonly sub: string;
  readonly preferred_username?: string;
  readonly name?: string;
  readonly email?: string;
  readonly given_name?: string;
  readonly family_name?: string;
}
