package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;

/** The colvenna-catalog identity a resource's jobs run as. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CatalogIdentity {
    public String principal;
    public String tokenId;
    public String tokenExpiresAt;

    @JsonPropertyDescription("A replaced token, revoked once the jobs run with the new one.")
    public String previousTokenId;

    public List<String> grantIds = new ArrayList<>();
    public String grantsExpireAt;
}
