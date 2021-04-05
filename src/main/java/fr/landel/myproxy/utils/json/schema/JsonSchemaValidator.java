package fr.landel.myproxy.utils.json.schema;

import java.io.Serializable;
import java.util.Set;

import fr.landel.myproxy.utils.json.JsonNode;
import fr.landel.myproxy.utils.json.JsonObject;
import fr.landel.myproxy.utils.json.JsonType;

public class JsonSchemaValidator {
    // private static final Logger LOGGER = LoggerFactory.getLogger(JsonSchemaValidator.class);

    // TODO check and append data (default) to input json object

    public static boolean validates(final JsonSchemaNode schema, final JsonNode json) {
        final var schemaSchema = schema.getSchema();
        if (schemaSchema.isRequired() && json == null) {
            return false;
        }

        boolean valid = true;

        switch (schemaSchema.getType()) {
        case NODE:
            valid &= checksNode(schema, json);
            break;
            
        case ARRAY:
        	//TODO
        	valid = false;
            break;
            
        case REFERENCE:
        	//TODO
        	valid = false;
            break;
            
        default:
        }

        return valid;
    }

    private static boolean checksNode(final JsonSchemaNode schema, final JsonNode json) {
        final var schemaSchema = schema.getSchema();

        final String id = schema.getId();
        final boolean required = schemaSchema.isRequired();
        final JsonSchemaType type = schemaSchema.getType();
        final JsonType jsonType = type.getCompatibleType();
        final Object def = schemaSchema.getDef();
        final Set<? extends Serializable> list = schemaSchema.getList();

        JsonObject<? extends Serializable> object = json.getChildren().get(id);
        if (object == null && required && def == null) {
            return false;
        } else if (object != null) {
            if (!jsonType.equals(object.getType())) {
                return false;
            } else if (list != null && !list.contains(object.getValue())) {
                return false;
            }
        }

        return true;
    }
}
