package io.quarkus.it.reactive.db2.client;

import java.util.concurrent.CompletionStage;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.db2client.DB2Pool;
import io.vertx.mutiny.sqlclient.Row;

@Path("/fruits")
public class FruitResource {

    @Inject
    DB2Pool client;

    @PostConstruct
    void setupDb() {
        client.query("DROP TABLE IF EXISTS fruits").execute()
                .flatMap(r -> client
                        .query("CREATE TABLE fruits (id INTEGER NOT NULL GENERATED AS IDENTITY, name VARCHAR(50) NOT NULL)")
                        .execute())
                .flatMap(r -> client.query("INSERT INTO fruits (name) VALUES ('Orange')").execute())
                .flatMap(r -> client.query("INSERT INTO fruits (name) VALUES ('Pear')").execute())
                .flatMap(r -> client.query("INSERT INTO fruits (name) VALUES ('Apple')").execute())
                .await().indefinitely();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public CompletionStage<JsonArray> listFruits() {
        return client.query("SELECT * FROM fruits").execute()
                .map(mysqlRowSet -> {
                    JsonArray jsonArray = new JsonArray();
                    for (Row row : mysqlRowSet) {
                        jsonArray.add(toJson(row));
                    }
                    return jsonArray;
                })
                .subscribeAsCompletionStage();
    }

    private JsonObject toJson(Row row) {
        return new JsonObject()
                .put("id", row.getLong("id"))
                .put("name", row.getString("name"));
    }

}
